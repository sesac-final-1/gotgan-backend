package com.sesac.gojigi.user.auth.service;

import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 가입 실패 뒤 Keycloak 에 남았을 수 있는 계정 정리 — 응답 경로 밖에서 최선 노력 (11/4 k6 경-2).
 * 타임아웃 뒤에도 Keycloak 이 처리 중일 수 있어 읽기 제한보다 늦게 찾는다. user_id 가 실패한 가입과 같은 계정만 지우므로 그 사이 다시 가입한 계정은 건드리지 않는다.
 * 스레드 하나로 차례로 처리해 과부하 때 Keycloak 부하를 키우지 않는다. 대기가 차거나 파드가 내려가 놓쳐도 재가입 때 정리된다 (AuthService).
 */
@Slf4j
@Component
public class SignupOrphanCleaner {

    private final KeycloakClientAdapter keycloakClientAdapter;
    private final Duration delay;
    private final int maxPending;
    private final AtomicInteger pending = new AtomicInteger();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "signup-orphan-cleaner");
        thread.setDaemon(true);
        return thread;
    });

    public SignupOrphanCleaner(KeycloakClientAdapter keycloakClientAdapter,
                               @Value("${app.keycloak.orphan-cleanup.delay:5s}") Duration delay,
                               @Value("${app.keycloak.orphan-cleanup.max-pending:500}") int maxPending) {
        this.keycloakClientAdapter = keycloakClientAdapter;
        this.delay = delay;
        this.maxPending = maxPending;
    }

    public void schedule(String email, long userId) {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet();
            log.warn("가입 실패 계정 정리 대기 초과 — 재가입 때 정리 userId={}", userId);
            return;
        }
        executor.schedule(() -> {
            try {
                clean(email, userId);
            } finally {
                pending.decrementAndGet();
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void clean(String email, long userId) {
        try {
            keycloakClientAdapter.findAccount(email)
                    .filter(account -> Long.valueOf(userId).equals(account.userId()))
                    .ifPresent(account -> {
                        keycloakClientAdapter.deleteUser(account.keycloakId(), null);
                        log.warn("가입 실패로 남은 Keycloak 계정 삭제 userId={}, keycloakId={}", userId, account.keycloakId());
                    });
        } catch (RuntimeException e) {
            log.warn("가입 실패 계정 정리 실패 — 재가입 때 정리 userId={}, cause={}", userId, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
