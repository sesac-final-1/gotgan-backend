package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreSummaryResponse;
import com.sesac.gojigi.store.store.dto.response.PublicStoreResponse;
import com.sesac.gojigi.store.store.dto.response.PublicStoreSummaryResponse;
import com.sesac.gojigi.store.store.dto.response.SellerStoreResponse;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import com.sesac.gojigi.store.store.repository.StoreWithApplicant;
import com.sesac.gojigi.store.store.repository.StoreWithCategory;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreQueryServiceTest {

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreApplicationRepository storeApplicationRepository;

    @Mock
    private StoreStatusHistoryRepository storeStatusHistoryRepository;

    @Mock
    private ImageUrls imageUrls;

    @InjectMocks
    private StoreQueryService storeQueryService;

    @Test
    @DisplayName("내부 상세는 기존 7필드(상태 · 배송 정책) 뒤에 신청서의 대표자 · 상호 · 사업자번호와 상태 이력(오래된 순, +09:00)을 붙인다")
    void getInternal_whenStoreExists_returnsDetailWithApplicantAndHistory() {
        // given
        Store store = Store.open(27L, 2L, "곳간상회", "gotgan", "<p>소개</p>", "010-0000-1234");
        ReflectionTestUtils.setField(store, "id", 1L);
        ReflectionTestUtils.setField(store, "status", StoreStatus.CLOSED);
        ReflectionTestUtils.setField(store, "statusReason", "판매자 폐점");
        ReflectionTestUtils.setField(store, "courier", "CJ대한통운");
        ReflectionTestUtils.setField(store, "dispatchNote", "평일 14시 이전 결제 시 당일 출고");
        ReflectionTestUtils.setField(store, "grade", StoreGrade.POWER);
        ReflectionTestUtils.setField(store, "createdAt", LocalDateTime.of(2026, 10, 15, 9, 0));
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        StoreApplication application = StoreApplication.builder()
                .userId(2L).categoryId(1L).bizType(BizType.INDIVIDUAL)
                .companyName("곳간상사").ownerName("홍길동").bizRegNo("000-00-00001").ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName("곳간상회").slug("gotgan").intro("<p>소개</p>")
                .representativeProducts(List.of(new RepresentativeProduct("리넨 셔츠", "셔츠", 39000)))
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(LocalDateTime.of(2026, 10, 10, 9, 0))
                .build();
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        StoreStatusHistory opened = StoreStatusHistory.of(1L, null, StoreStatus.ACTIVE, StoreActorType.ADMIN, null);
        ReflectionTestUtils.setField(opened, "createdAt", LocalDateTime.of(2026, 10, 15, 9, 0));
        StoreStatusHistory closed = StoreStatusHistory.of(1L, StoreStatus.ACTIVE, StoreStatus.CLOSED, StoreActorType.SELLER, "판매자 폐점");
        ReflectionTestUtils.setField(closed, "createdAt", LocalDateTime.of(2026, 10, 28, 14, 30));
        given(storeStatusHistoryRepository.findAllByStoreIdOrderByIdAsc(1L)).willReturn(List.of(opened, closed));

        // when
        InternalStoreResponse response = storeQueryService.getInternal(1L);

        // then
        assertThat(response).isEqualTo(new InternalStoreResponse(1L, 2L, "곳간상회", StoreStatus.CLOSED, 3000L, null, "CJ대한통운",
                "gotgan", "홍길동", "곳간상사", "000-00-00001", "010-0000-1234", StoreGrade.POWER, "평일 14시 이전 결제 시 당일 출고",
                "판매자 폐점", 27L, OffsetDateTime.parse("2026-10-15T09:00:00+09:00"), List.of(
                        new InternalStoreResponse.History(null, StoreStatus.ACTIVE, StoreActorType.ADMIN, null,
                                OffsetDateTime.parse("2026-10-15T09:00:00+09:00")),
                        new InternalStoreResponse.History(StoreStatus.ACTIVE, StoreStatus.CLOSED, StoreActorType.SELLER, "판매자 폐점",
                                OffsetDateTime.parse("2026-10-28T14:30:00+09:00")))));
    }

    @Test
    @DisplayName("없거나 소프트 삭제된 스토어는 STORE_NOT_FOUND")
    void getInternal_whenStoreMissing_throwsNotFound() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> storeQueryService.getInternal(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
    }

    @Test
    @DisplayName("관리자 목록 한 줄은 스토어 · 신청서 값을 담고 개설 시각을 +09:00 으로 바꾼다")
    void getAdminList_withStatus_mapsSummary() {
        // given
        Store store = mock(Store.class);
        given(store.getId()).willReturn(15L);
        given(store.getStoreName()).willReturn("볕드는집");
        given(store.getSlug()).willReturn("sunny-house");
        given(store.getUserId()).willReturn(205L);
        given(store.getGrade()).willReturn(StoreGrade.SPROUT);
        given(store.getStatus()).willReturn(StoreStatus.SUSPENDED);
        given(store.getStatusReason()).willReturn("허위 원산지 표기 신고 확인");
        given(store.getCreatedAt()).willReturn(LocalDateTime.of(2025, 12, 31, 10, 0));
        given(storeRepository.findAllForAdmin(StoreStatus.SUSPENDED))
                .willReturn(List.of(new StoreWithApplicant(store, "윤하늘", "000-00-00005")));

        // when
        List<InternalStoreSummaryResponse> result = storeQueryService.getAdminList(StoreStatus.SUSPENDED);

        // then
        assertThat(result).containsExactly(new InternalStoreSummaryResponse(15L, "볕드는집", "sunny-house", 205L, "윤하늘",
                "000-00-00005", StoreGrade.SPROUT, StoreStatus.SUSPENDED, "허위 원산지 표기 신고 확인",
                OffsetDateTime.parse("2025-12-31T10:00:00+09:00")));
    }

    @Test
    @DisplayName("status 가 null 이면 그대로 넘겨 전체를 조회한다 — 없으면 빈 목록")
    void getAdminList_whenStatusNull_returnsEmpty() {
        // given
        given(storeRepository.findAllForAdmin(null)).willReturn(List.of());

        // when · then
        assertThat(storeQueryService.getAdminList(null)).isEmpty();
    }

    @Test
    @DisplayName("공개 스토어 목록 — 신청서 카테고리 · 이미지 URL(없으면 null) · 개설 시각 +09:00, 순서는 저장소 그대로")
    void getPublicList_mapsCategoryAndImageUrl() {
        // given
        Store withImage = Store.open(27L, 2L, "곳간상회", "gotgan", "<p>소개</p>", "010-0000-1234");
        ReflectionTestUtils.setField(withImage, "id", 1L);
        ReflectionTestUtils.setField(withImage, "imageKey", "stores/1/a.jpg");
        ReflectionTestUtils.setField(withImage, "color", "#2b2b2b");
        ReflectionTestUtils.setField(withImage, "grade", StoreGrade.POWER);
        ReflectionTestUtils.setField(withImage, "createdAt", LocalDateTime.of(2026, 10, 15, 9, 0));
        Store noImage = Store.open(28L, 3L, "토담공방", "todam", null, "063-0000-0004");
        ReflectionTestUtils.setField(noImage, "id", 2L);
        ReflectionTestUtils.setField(noImage, "color", "#8c6d4f");
        ReflectionTestUtils.setField(noImage, "grade", StoreGrade.SPROUT);
        ReflectionTestUtils.setField(noImage, "createdAt", LocalDateTime.of(2026, 10, 1, 9, 0));
        given(storeRepository.findAllActiveForPublic())
                .willReturn(List.of(new StoreWithCategory(withImage, 3L), new StoreWithCategory(noImage, 2L)));
        given(imageUrls.of("stores/1/a.jpg")).willReturn("https://images/stores/1/a.jpg");

        // when
        List<PublicStoreSummaryResponse> result = storeQueryService.getPublicList();

        // then
        assertThat(result).containsExactly(
                new PublicStoreSummaryResponse(1L, "곳간상회", "gotgan", 3L, "<p>소개</p>", "https://images/stores/1/a.jpg",
                        "#2b2b2b", StoreGrade.POWER, OffsetDateTime.parse("2026-10-15T09:00:00+09:00")),
                new PublicStoreSummaryResponse(2L, "토담공방", "todam", 2L, null, null,
                        "#8c6d4f", StoreGrade.SPROUT, OffsetDateTime.parse("2026-10-01T09:00:00+09:00")));
    }

    @Test
    @DisplayName("공개 스토어 홈 — 운영 중이면 신청서의 카테고리 · 대표자 · 사업자번호와 배송 정책 · 이미지 URL")
    void getPublic_whenActive_returnsHome() {
        // given
        Store store = Store.open(27L, 2L, "곳간상회", "gotgan", "<p>소개</p>", "010-0000-1234");
        ReflectionTestUtils.setField(store, "id", 1L);
        ReflectionTestUtils.setField(store, "imageKey", "stores/1/a.jpg");
        ReflectionTestUtils.setField(store, "color", "#2b2b2b");
        ReflectionTestUtils.setField(store, "grade", StoreGrade.POWER);
        ReflectionTestUtils.setField(store, "courier", "CJ대한통운");
        ReflectionTestUtils.setField(store, "createdAt", LocalDateTime.of(2026, 10, 15, 9, 0));
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(publicApplication()));
        given(imageUrls.of("stores/1/a.jpg")).willReturn("https://images/stores/1/a.jpg");

        // when
        PublicStoreResponse home = storeQueryService.getPublic(1L);

        // then
        assertThat(home).isEqualTo(new PublicStoreResponse(1L, "곳간상회", StoreStatus.ACTIVE, "gotgan", 3L, "<p>소개</p>",
                "https://images/stores/1/a.jpg", "#2b2b2b", StoreGrade.POWER, OffsetDateTime.parse("2026-10-15T09:00:00+09:00"),
                "홍길동", "000-00-00001", "010-0000-1234", 3000L, null, "CJ대한통운", null));
    }

    @Test
    @DisplayName("공개 스토어 홈 — 정지 · 폐점이면 이름 · 상태만, 신청서는 읽지 않는다 / 없으면 404")
    void getPublic_whenNotActive_returnsNameAndStatusOnly() {
        // given
        Store suspended = Store.open(27L, 2L, "곳간상회", "gotgan", "<p>소개</p>", "010-0000-1234");
        ReflectionTestUtils.setField(suspended, "id", 1L);
        suspended.changeStatusByAdmin(StoreStatus.SUSPENDED, "허위 광고");
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(suspended));
        given(storeRepository.findByIdAndDeletedAtIsNull(9L)).willReturn(Optional.empty());

        // when
        PublicStoreResponse home = storeQueryService.getPublic(1L);

        // then
        assertThat(home).isEqualTo(new PublicStoreResponse(1L, "곳간상회", StoreStatus.SUSPENDED, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
        then(storeApplicationRepository).should(never()).findById(any());
        assertThatThrownBy(() -> storeQueryService.getPublic(9L))
                .extracting("errorCode").isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
    }

    private static StoreApplication publicApplication() {
        return StoreApplication.builder()
                .userId(2L).categoryId(3L).bizType(BizType.INDIVIDUAL)
                .companyName("곳간상사").ownerName("홍길동").bizRegNo("000-00-00001").ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName("곳간상회").slug("gotgan").intro("<p>소개</p>")
                .representativeProducts(List.of(new RepresentativeProduct("리넨 셔츠", "셔츠", 39000)))
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(LocalDateTime.of(2026, 10, 10, 9, 0))
                .build();
    }

    @Test
    @DisplayName("판매자 내 스토어 — 이미지 키를 조회 URL 로 바꾸고 대표자 · 사업자번호는 신청서에서, 정지 사유까지")
    void getSellerStore_whenStoreExists_returnsSettingsWithImageUrl() {
        // given
        Store store = Store.open(27L, 2L, "곳간상회", "gotgan", "<p>소개</p>", "010-0000-1234");
        ReflectionTestUtils.setField(store, "id", 1L);
        ReflectionTestUtils.setField(store, "imageKey", "stores/1/a.jpg");
        ReflectionTestUtils.setField(store, "color", "#2b2b2b");
        ReflectionTestUtils.setField(store, "grade", StoreGrade.SPROUT);
        ReflectionTestUtils.setField(store, "createdAt", LocalDateTime.of(2026, 10, 15, 9, 0));
        store.changeStatusByAdmin(StoreStatus.SUSPENDED, "허위 광고");
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        StoreApplication application = StoreApplication.builder()
                .userId(2L).categoryId(1L).bizType(BizType.INDIVIDUAL)
                .companyName("곳간상사").ownerName("홍길동").bizRegNo("000-00-00001").ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName("곳간상회").slug("gotgan").intro("<p>소개</p>")
                .representativeProducts(List.of(new RepresentativeProduct("리넨 셔츠", "셔츠", 39000)))
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(LocalDateTime.of(2026, 10, 10, 9, 0))
                .build();
        given(storeApplicationRepository.findById(27L)).willReturn(Optional.of(application));
        given(imageUrls.of("stores/1/a.jpg")).willReturn("https://images/stores/1/a.jpg");

        // when
        SellerStoreResponse mine = storeQueryService.getSellerStore(1L);

        // then
        assertThat(mine).isEqualTo(new SellerStoreResponse(1L, "곳간상회", "gotgan", StoreStatus.SUSPENDED, "허위 광고", "<p>소개</p>",
                "stores/1/a.jpg", "https://images/stores/1/a.jpg", "#2b2b2b", "010-0000-1234", 3000L, null, null, null,
                StoreGrade.SPROUT, "홍길동", "000-00-00001", OffsetDateTime.parse("2026-10-15T09:00:00+09:00")));
    }

    @Test
    @DisplayName("판매자 내 스토어 — 없거나 소프트 삭제면 STORE_NOT_FOUND")
    void getSellerStore_whenStoreMissing_throwsNotFound() {
        // given
        given(storeRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> storeQueryService.getSellerStore(99L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
    }
}
