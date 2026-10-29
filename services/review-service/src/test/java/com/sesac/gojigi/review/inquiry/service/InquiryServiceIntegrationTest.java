package com.sesac.gojigi.review.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader.InquirySnapshot;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryCreateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryUpdateRequest;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryResponse;
import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import com.sesac.gojigi.review.inquiry.repository.ProductInquiryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 상품 문의 등록 · 수정 · 삭제 — 스냅샷 저장 · 본문 정화 · 본인 · 답변 후 수정 막기 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, InquiryService.class, InquiryServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋
class InquiryServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 10, 0);
    private static final long BUYER = 4L;
    private static final long OTHER = 5L;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private ProductInquiryRepository inquiryRepository;
    @MockitoBean
    private InquirySnapshotReader snapshotReader;

    private long saveInquiry(long userId) {
        return inquiryRepository.save(ProductInquiry.builder()
                .userId(userId).productId(101L).storeId(7L).productName("린넨 오버핏 셔츠").writerDisplayName("곳간러버")
                .content("M이랑 L 중 어떤 걸 추천하시나요?").secret(false)
                .build()).getId();
    }

    private ProductInquiry reload(long inquiryId) {
        return inquiryRepository.findById(inquiryId).orElseThrow();
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Nested
    @DisplayName("등록")
    class Write {

        @Test
        @DisplayName("상품 · 회원 스냅샷과 정화한 본문을 저장한다 — storeId 는 상품에서")
        void write_savesSnapshotAndSanitizedContent() {
            // given
            given(snapshotReader.read(BUYER, 101L))
                    .willReturn(new InquirySnapshot(7L, "린넨 오버핏 셔츠", "https://cdn/p.jpg", "곳간러버"));

            // when
            long inquiryId = inquiryService.write(BUYER,
                    new InquiryCreateRequest(101L, "<b>세이지</b> 컬러 재입고 언제 되나요?<script>x</script>", true)).inquiryId();

            // then
            ProductInquiry saved = reload(inquiryId);
            assertThat(saved.getUserId()).isEqualTo(BUYER);
            assertThat(saved.getStoreId()).isEqualTo(7L);
            assertThat(saved.getProductName()).isEqualTo("린넨 오버핏 셔츠");
            assertThat(saved.getThumbnailUrl()).isEqualTo("https://cdn/p.jpg");
            assertThat(saved.getWriterDisplayName()).isEqualTo("곳간러버");
            assertThat(saved.getContent()).isEqualTo("세이지 컬러 재입고 언제 되나요?");
            assertThat(saved.isSecret()).isTrue();
            assertThat(saved.isAnswered()).isFalse();
        }

        @Test
        @DisplayName("태그를 지우면 남는 글자가 없음 — 400 INVALID_INPUT, 다른 서비스를 부르지 않는다")
        void write_onlyTags_rejectedBeforeRemoteCall() {
            Throwable thrown = catchThrowable(
                    () -> inquiryService.write(BUYER, new InquiryCreateRequest(101L, "<script>alert(1)</script>", false)));

            assertCode(thrown, CommonErrorCode.INVALID_INPUT);
            verify(snapshotReader, never()).read(anyLong(), anyLong());
        }

        @Test
        @DisplayName("없는 상품 — 스냅샷 오류 그대로, 저장하지 않는다")
        void write_productNotFound_notSaved() {
            given(snapshotReader.read(BUYER, 999L)).willThrow(new InquiryException(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND));
            long before = inquiryRepository.count();

            assertThatThrownBy(() -> inquiryService.write(BUYER, new InquiryCreateRequest(999L, "재입고 되나요?", false)))
                    .isInstanceOf(InquiryException.class);
            assertThat(inquiryRepository.count()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("수정")
    class Edit {

        @Test
        @DisplayName("답변 전 내 문의 — 본문 · 비밀글을 바꾸고 수정된 값을 돌려준다")
        void edit_ownUnanswered_updates() {
            // given
            long inquiryId = saveInquiry(BUYER);

            // when
            InquiryResponse response = inquiryService.edit(BUYER, inquiryId,
                    new InquiryUpdateRequest("어깨가 <i>넓은</i> 편인데 L 이 나을까요?", true));

            // then
            assertThat(response.content()).isEqualTo("어깨가 넓은 편인데 L 이 나을까요?");
            assertThat(response.secret()).isTrue();
            assertThat(response.productName()).isEqualTo("린넨 오버핏 셔츠");
            assertThat(response.answerContent()).isNull();
            assertThat(response.createdAt()).isNotNull();
            ProductInquiry saved = reload(inquiryId);
            assertThat(saved.getContent()).isEqualTo("어깨가 넓은 편인데 L 이 나을까요?");
            assertThat(saved.isSecret()).isTrue();
        }

        @Test
        @DisplayName("판매자가 답변한 문의 — 409 INQUIRY_ALREADY_ANSWERED, 그대로")
        void edit_answered_rejected() {
            // given
            long inquiryId = saveInquiry(BUYER);
            ProductInquiry inquiry = reload(inquiryId);
            inquiry.answer("M 추천드립니다", NOW);
            inquiryRepository.save(inquiry);

            // when
            Throwable thrown = catchThrowable(
                    () -> inquiryService.edit(BUYER, inquiryId, new InquiryUpdateRequest("바꿀래요", true)));

            // then
            assertCode(thrown, InquiryErrorCode.INQUIRY_ALREADY_ANSWERED);
            assertThat(reload(inquiryId).getContent()).isEqualTo("M이랑 L 중 어떤 걸 추천하시나요?");
        }

        @Test
        @DisplayName("남의 문의 · 삭제된 문의 · 없는 문의 — 같은 404")
        void edit_notOwnOrDeleted_notFound() {
            long others = saveInquiry(OTHER);
            long deleted = saveInquiry(BUYER);
            inquiryService.delete(BUYER, deleted);
            InquiryUpdateRequest request = new InquiryUpdateRequest("바꿀래요", false);

            for (long inquiryId : new long[] { others, deleted, Long.MAX_VALUE }) {
                Throwable thrown = catchThrowable(() -> inquiryService.edit(BUYER, inquiryId, request));
                assertCode(thrown, InquiryErrorCode.INQUIRY_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("내 문의 — 답변이 달려 있어도 소프트 삭제")
        void delete_ownAnswered_softDeleted() {
            // given
            long inquiryId = saveInquiry(BUYER);
            ProductInquiry inquiry = reload(inquiryId);
            inquiry.answer("다음 주 재입고 예정입니다", NOW);
            inquiryRepository.save(inquiry);

            // when
            inquiryService.delete(BUYER, inquiryId);

            // then
            ProductInquiry deleted = reload(inquiryId);
            assertThat(deleted.isDeleted()).isTrue();
            assertThat(deleted.getDeletedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("남의 문의 · 이미 삭제된 문의 — 404, 남의 문의는 그대로")
        void delete_notOwnOrDeleted_notFound() {
            long others = saveInquiry(OTHER);
            long deleted = saveInquiry(BUYER);
            inquiryService.delete(BUYER, deleted);

            assertCode(catchThrowable(() -> inquiryService.delete(BUYER, others)),
                    InquiryErrorCode.INQUIRY_NOT_FOUND);
            assertCode(catchThrowable(() -> inquiryService.delete(BUYER, deleted)),
                    InquiryErrorCode.INQUIRY_NOT_FOUND);
            assertThat(reload(others).isDeleted()).isFalse();
        }
    }
}
