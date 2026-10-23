package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import com.sesac.gojigi.user.auth.client.dto.SellerGrantResult;
import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.user.dto.event.StoreApprovedPayload;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 MySQL — processed_event 로 같은 eventId 재전달을 건너뛰는지, 실패면 처리됨으로 남기지 않는지. Keycloak 은 목.
 * 서비스가 트랜잭션 없이 저장(커밋)해서 테스트 트랜잭션으로 감싸지 않는다 (끝나고 직접 정리).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, SellerConversionService.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SellerConversionServiceIntegrationTest {

    @Autowired
    private SellerConversionService sellerConversionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @MockitoBean
    private KeycloakClientAdapter keycloakClientAdapter;

    private final List<Long> users = new ArrayList<>();
    private final List<String> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        processedEventRepository.findAll().stream()
                .filter(e -> eventIds.contains(e.getEventId()))
                .forEach(processedEventRepository::delete);
        userRepository.deleteAllByIdInBatch(users);
    }

    private User member() {
        User user = userRepository.save(User.signUp("seller-" + System.nanoTime() + "@gotgan.com", "판매 신청자", null, null));
        users.add(user.getId());
        return user;
    }

    private EventEnvelope<StoreApprovedPayload> approved(Long userId, long storeId) {
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);
        return new EventEnvelope<>(eventId, "StoreApproved", OffsetDateTime.now(), String.valueOf(storeId), 1,
                new StoreApprovedPayload(storeId, userId));
    }

    @Test
    @DisplayName("승인 → Keycloak 에 SELLER · store_id, processed_event 저장 — 같은 eventId 재전달은 Keycloak 을 다시 부르지 않음")
    void applyApproved_grantsOnceAndSkipsDuplicate() {
        User user = member();
        EventEnvelope<StoreApprovedPayload> event = approved(user.getId(), 3L);
        given(keycloakClientAdapter.grantSeller(user.getKeycloakId(), user.getEmail(), 3L)).willReturn(SellerGrantResult.GRANTED);

        assertThat(sellerConversionService.applyApproved(event)).isEqualTo(SellerConversionService.Result.GRANTED);
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isTrue();

        assertThat(sellerConversionService.applyApproved(event)).isEqualTo(SellerConversionService.Result.DUPLICATE);
        verify(keycloakClientAdapter).grantSeller(user.getKeycloakId(), user.getEmail(), 3L);
    }

    @Test
    @DisplayName("Keycloak 장애면 예외 그대로 · processed_event 없음 — 메시지가 남아 재전달되면 다시 처리")
    void applyApproved_keycloakDown_notMarked() {
        User user = member();
        EventEnvelope<StoreApprovedPayload> event = approved(user.getId(), 3L);
        given(keycloakClientAdapter.grantSeller(any(), anyString(), anyLong()))
                .willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버에 연결할 수 없습니다."));

        assertThatThrownBy(() -> sellerConversionService.applyApproved(event)).isInstanceOf(BusinessException.class);
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isFalse();
    }

    @Test
    @DisplayName("다른 store_id 가 이미 있거나 계정이 없으면 바꾸지 않고 처리됨으로 남긴다 — 재전달해도 결과가 같아서")
    void applyApproved_mismatchOrNoAccount_marked() {
        User user = member();
        EventEnvelope<StoreApprovedPayload> mismatch = approved(user.getId(), 3L);
        EventEnvelope<StoreApprovedPayload> noAccount = approved(user.getId(), 4L);
        given(keycloakClientAdapter.grantSeller(user.getKeycloakId(), user.getEmail(), 3L)).willReturn(SellerGrantResult.STORE_MISMATCH);
        given(keycloakClientAdapter.grantSeller(user.getKeycloakId(), user.getEmail(), 4L)).willReturn(SellerGrantResult.ACCOUNT_NOT_FOUND);

        assertThat(sellerConversionService.applyApproved(mismatch)).isEqualTo(SellerConversionService.Result.STORE_MISMATCH);
        assertThat(sellerConversionService.applyApproved(noAccount)).isEqualTo(SellerConversionService.Result.ACCOUNT_NOT_FOUND);
        assertThat(processedEventRepository.existsByEventId(mismatch.eventId())).isTrue();
        assertThat(processedEventRepository.existsByEventId(noAccount.eventId())).isTrue();
    }

    @Test
    @DisplayName("회원이 없으면(탈퇴 · 잘못된 userId) Keycloak 을 부르지 않고 처리됨으로 남긴다")
    void applyApproved_unknownUser_skips() {
        EventEnvelope<StoreApprovedPayload> event = approved(Long.MAX_VALUE, 3L);

        assertThat(sellerConversionService.applyApproved(event)).isEqualTo(SellerConversionService.Result.USER_NOT_FOUND);
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isTrue();
        verify(keycloakClientAdapter, never()).grantSeller(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("이미 SELLER · 같은 store_id (다른 eventId 로 다시 승인) — ALREADY_GRANTED")
    void applyApproved_alreadyGranted() {
        User user = member();
        EventEnvelope<StoreApprovedPayload> event = approved(user.getId(), 3L);
        given(keycloakClientAdapter.grantSeller(user.getKeycloakId(), user.getEmail(), 3L)).willReturn(SellerGrantResult.ALREADY_GRANTED);

        assertThat(sellerConversionService.applyApproved(event)).isEqualTo(SellerConversionService.Result.ALREADY_GRANTED);
    }
}
