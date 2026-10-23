package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import com.sesac.gojigi.user.auth.client.dto.SellerGrantResult;
import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.ProcessedEvent;
import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.user.dto.event.StoreApprovedPayload;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 판매자 전환 (StoreApproved) — 입점 승인된 회원의 Keycloak 계정에 store_id 속성 · SELLER 역할을 넣는다.
 * 역할 · store_id 는 Keycloak 에만 있다 (userdb 는 바꾸지 않음). 회원이 다시 로그인하거나 토큰을 재발급받으면 반영된다.
 * - 같은 eventId 재전달은 processed_event 로 건너뛴다. Keycloak 호출은 이미 있는 것을 건너뛰어 다시 해도 같은 결과라
 *   호출 뒤 processed_event 를 저장한다 (외부 호출 동안 트랜잭션을 잡지 않음)
 * - Keycloak 장애는 예외 그대로 → 메시지를 지우지 않아 재전달 (SqsEventPoller)
 * - 회원 · 계정이 없거나 다른 store_id 가 이미 있으면 바꾸지 않고 warn — 재전달해도 같으니 처리됨으로 남긴다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerConversionService {

    public enum Result { GRANTED, ALREADY_GRANTED, STORE_MISMATCH, ACCOUNT_NOT_FOUND, USER_NOT_FOUND, DUPLICATE }

    private final UserRepository userRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final KeycloakClientAdapter keycloakClientAdapter;

    public Result applyApproved(EventEnvelope<StoreApprovedPayload> event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return Result.DUPLICATE;
        }
        StoreApprovedPayload p = event.payload();
        Result result = userRepository.findByIdAndDeletedAtIsNull(p.userId())
                .map(user -> grant(user, p.storeId()))
                .orElse(Result.USER_NOT_FOUND);
        markProcessed(event);
        logResult(result, p, event.eventId());
        return result;
    }

    private Result grant(User user, Long storeId) {
        SellerGrantResult granted = keycloakClientAdapter.grantSeller(user.getKeycloakId(), user.getEmail(), storeId);
        return switch (granted) {
            case GRANTED -> Result.GRANTED;
            case ALREADY_GRANTED -> Result.ALREADY_GRANTED;
            case STORE_MISMATCH -> Result.STORE_MISMATCH;
            case ACCOUNT_NOT_FOUND -> Result.ACCOUNT_NOT_FOUND;
        };
    }

    /** 같은 eventId 가 동시에 처리되면 uk_processed_event_event_id 가 한쪽을 막는다 — Keycloak 반영은 같으니 그대로 성공 */
    private void markProcessed(EventEnvelope<StoreApprovedPayload> event) {
        try {
            processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        } catch (DataIntegrityViolationException e) {
            log.info("StoreApproved 동시 처리 — 이미 처리됨 eventId={}", event.eventId());
        }
    }

    private static void logResult(Result result, StoreApprovedPayload p, String eventId) {
        switch (result) {
            case GRANTED, ALREADY_GRANTED -> log.info("판매자 전환 userId={} storeId={} result={} eventId={}",
                    p.userId(), p.storeId(), result, eventId);
            case STORE_MISMATCH -> log.warn("판매자 전환 건너뜀 — 이미 다른 store_id 가 있음(1인 1스토어) userId={} storeId={} eventId={}",
                    p.userId(), p.storeId(), eventId);
            default -> log.warn("판매자 전환 건너뜀 — 회원 · 인증 계정 없음 userId={} storeId={} result={} eventId={}",
                    p.userId(), p.storeId(), result, eventId);
        }
    }
}
