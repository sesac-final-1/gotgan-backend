package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.SellerStoreResponse;
import com.sesac.gojigi.store.store.entity.StoreActorType;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** 실제 MySQL + Flyway + 시연 시드 위에서 1절 확장 응답(신청서 값 · 출고 안내 · 등급 · 상태 이력)이 채워지는지 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, StoreQueryService.class, ImageUrls.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class StoreQueryServiceIntegrationTest {

    @Autowired
    private StoreQueryService storeQueryService;

    @Test
    @DisplayName("시드 정지 스토어 15 — 신청서의 대표자 · 사업자번호, 등급 · 사유, 이력 개설 → 정지 (오래된 순)")
    void getInternal_seedSuspendedStore_returnsAdminFieldsAndHistory() {
        // when
        InternalStoreResponse detail = storeQueryService.getInternal(15L);

        // then — 기존 필드 그대로
        assertThat(detail.storeId()).isEqualTo(15L);
        assertThat(detail.status()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(detail.shippingFee()).isNotNull();
        // 1절 확장
        assertThat(detail.ownerName()).isEqualTo("정다은");
        assertThat(detail.bizRegNo()).isEqualTo("000-00-00005");
        assertThat(detail.companyName()).isNotBlank();
        assertThat(detail.slug()).isEqualTo("byeot-house");
        assertThat(detail.grade()).isEqualTo(StoreGrade.SPROUT);
        assertThat(detail.statusReason()).isEqualTo("반품 미처리 3건 — 처리 완료 후 해제");
        assertThat(detail.applicationId()).isEqualTo(15L);
        assertThat(detail.createdAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
        assertThat(detail.statusHistory()).extracting(InternalStoreResponse.History::toStatus)
                .containsExactly(StoreStatus.ACTIVE, StoreStatus.SUSPENDED);
        assertThat(detail.statusHistory().get(1)).satisfies(h -> {
            assertThat(h.fromStatus()).isEqualTo(StoreStatus.ACTIVE);
            assertThat(h.actor()).isEqualTo(StoreActorType.ADMIN);
            assertThat(h.reason()).isEqualTo("반품 미처리 3건 — 처리 완료 후 해제");
            assertThat(h.changedAt()).isAfter(detail.createdAt());
        });
    }

    @Test
    @DisplayName("시드 스토어 1 — 출고 안내 문구가 매핑되고 이력은 개설 1건")
    void getInternal_seedStore_mapsDispatchNote() {
        // when
        InternalStoreResponse detail = storeQueryService.getInternal(1L);

        // then
        assertThat(detail.ownerName()).isEqualTo("박영철");
        assertThat(detail.dispatchNote()).isNotBlank();
        assertThat(detail.statusHistory()).singleElement().satisfies(h -> {
            assertThat(h.fromStatus()).isNull();
            assertThat(h.toStatus()).isEqualTo(StoreStatus.ACTIVE);
        });
    }

    @Test
    @DisplayName("판매자 내 스토어 — 시드 스토어 1 의 색 · 연락처 · 배송 정책 · 출고 안내 · 대표자, 이미지 없음은 null")
    void getSellerStore_seedStore_returnsSettings() {
        // when
        SellerStoreResponse mine = storeQueryService.getSellerStore(1L);

        // then
        assertThat(mine.storeName()).isEqualTo("산골농부 박씨네");
        assertThat(mine.slug()).isEqualTo("park-farm");
        assertThat(mine.color()).isEqualTo("#d0701f");
        assertThat(mine.csPhone()).isEqualTo("064-0000-0006");
        assertThat(mine.shippingFee()).isEqualTo(3000L);
        assertThat(mine.freeShippingThreshold()).isEqualTo(30000L);
        assertThat(mine.courier()).isEqualTo("우체국택배");
        assertThat(mine.dispatchNote()).isNotBlank();
        assertThat(mine.imageKey()).isNull();
        assertThat(mine.imageUrl()).isNull();
        assertThat(mine.ownerName()).isEqualTo("박영철");
        assertThat(mine.bizRegNo()).isEqualTo("000-00-00006");
        assertThat(mine.createdAt().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
    }
}
