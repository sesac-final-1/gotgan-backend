package com.sesac.gojigi.user.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.auth.client.dto.KeycloakServiceAccountToken;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Bulkhead · 서킷 AOP 를 실제로 거쳐 application.yml 설정대로 — 동시 10개까지, 넘으면 AUTH_SIGNUP_BUSY, 거절은 서킷에 집계되지 않는다 */
@SpringBootTest(classes = KeycloakClientAdapter.class)
@ImportAutoConfiguration({ AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class,
        BulkheadAutoConfiguration.class })
class KeycloakSignupBulkheadTest {

    private static final int LIMIT = 10;

    @MockitoBean KeycloakClient keycloakClient;
    @MockitoBean KeycloakAdminClient keycloakAdminClient;
    @MockitoBean JsonMapper jsonMapper;
    @Autowired KeycloakClientAdapter adapter;
    @Autowired CircuitBreakerRegistry circuitBreakers;

    @Test
    @DisplayName("동시 10개가 진행 중이면 대기 500ms 뒤 AUTH_SIGNUP_BUSY — Keycloak 을 부르지 않고 서킷도 열리지 않는다")
    void createUser_overLimit_rejectedWithoutOpeningCircuit() throws Exception {
        // given — 10개가 Keycloak 호출에 묶여 있다
        CountDownLatch entered = new CountDownLatch(LIMIT);
        CountDownLatch release = new CountDownLatch(1);
        given(keycloakClient.serviceAccountToken(any())).willReturn(new KeycloakServiceAccountToken("sa"));
        given(keycloakAdminClient.createUser(any(), any())).willAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return ResponseEntity.created(URI.create("http://keycloak/users/kc-1")).build();
        });
        ExecutorService pool = Executors.newFixedThreadPool(LIMIT);
        List<Future<String>> running = new ArrayList<>();
        for (int i = 0; i < LIMIT; i++) {
            long userId = i;
            running.add(pool.submit(() -> adapter.createUser("a" + userId + "@gotgan.com", "Dem0!pass", userId)));
        }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // when · then — 거절을 여러 번 받아도 (서킷 최소 호출 5 · 실패율 50%)
        long start = System.nanoTime();
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> adapter.createUser("over@gotgan.com", "Dem0!pass", 99L))
                    .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_SIGNUP_BUSY);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(6 * 400L);   // 거절마다 대기 500ms

        CircuitBreaker circuit = circuitBreakers.circuitBreaker("keycloak");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();   // 진행 중 10개는 아직 안 끝났고, 거절은 집계하지 않음
        verify(keycloakAdminClient, times(LIMIT)).createUser(any(), any());

        // 자리가 나면 다시 된다
        release.countDown();
        for (Future<String> call : running) {
            assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("kc-1");
        }
        pool.shutdownNow();
        assertThat(adapter.createUser("again@gotgan.com", "Dem0!pass", 100L)).isEqualTo("kc-1");
    }
}
