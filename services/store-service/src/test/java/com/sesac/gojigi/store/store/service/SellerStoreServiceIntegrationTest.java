package com.sesac.gojigi.store.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.TestcontainersConfiguration;
import com.sesac.gojigi.store.store.client.StoreImageClient;
import com.sesac.gojigi.store.store.dto.request.SellerStoreUpdateRequest;
import com.sesac.gojigi.store.store.dto.response.SellerStoreResponse;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 실제 MySQL + Flyway + 시연 시드 위에서 설정 수정이 컬럼에 저장되고 상태 · 등급 · 이름은 그대로인지 확인 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, SellerStoreService.class, StoreQueryService.class, ImageUrls.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/seed")
class SellerStoreServiceIntegrationTest {

    @MockitoBean
    private StoreImageClient storeImageClient;

    @Autowired
    private SellerStoreService service;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("시드 정지 스토어 15 설정 수정 — 설정 컬럼만 바뀌고 상태 · 사유 · 등급 · 이름 · 주소는 그대로, 응답은 바뀐 값")
    void update_seedSuspendedStore_persistsSettingsOnly() {
        // when
        SellerStoreResponse response = service.update(15L, new SellerStoreUpdateRequest("<p>새 소개</p>", "stores/15/new-cover.jpg",
                "#2f5f8a", "070-0000-1234", 0L, null, "롯데택배", "주문 후 2일 안 출고"));
        entityManager.flush();
        entityManager.clear();

        // then
        Map<String, Object> row = jdbc.queryForMap("SELECT intro, image_key, color, cs_phone, shipping_fee, free_shipping_threshold, "
                + "courier, dispatch_note, status, status_reason, grade, store_name, slug FROM store WHERE store_id = 15");
        assertThat(row).containsEntry("intro", "<p>새 소개</p>").containsEntry("image_key", "stores/15/new-cover.jpg")
                .containsEntry("color", "#2f5f8a").containsEntry("cs_phone", "070-0000-1234").containsEntry("shipping_fee", 0L)
                .containsEntry("courier", "롯데택배").containsEntry("dispatch_note", "주문 후 2일 안 출고")
                .containsEntry("status", "SUSPENDED").containsEntry("status_reason", "반품 미처리 3건 — 처리 완료 후 해제")
                .containsEntry("grade", "SPROUT").containsEntry("store_name", "볕드는집").containsEntry("slug", "byeot-house");
        assertThat(row.get("free_shipping_threshold")).isNull();
        assertThat(response.imageUrl()).endsWith("/stores/15/new-cover.jpg");
        assertThat(response.status()).isEqualTo(StoreStatus.SUSPENDED);
    }

    @Test
    @DisplayName("폐점 스토어는 409 STORE_CLOSED 이고 값이 바뀌지 않는다")
    void update_closedStore_rejected() {
        // given
        jdbc.update("UPDATE store SET status = 'CLOSED' WHERE store_id = 2");

        // when · then
        assertThatThrownBy(() -> service.update(2L, new SellerStoreUpdateRequest(null, null, "#2b2b2b", "02-0000-1234", 0L, null, null, null)))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(StoreErrorCode.STORE_CLOSED);
        entityManager.clear();
        assertThat(jdbc.queryForObject("SELECT shipping_fee FROM store WHERE store_id = 2", Long.class)).isNotEqualTo(0L);
    }
}
