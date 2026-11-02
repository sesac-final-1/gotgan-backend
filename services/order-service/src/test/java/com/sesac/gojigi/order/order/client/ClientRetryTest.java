package com.sesac.gojigi.order.order.client;

import static com.sesac.gojigi.order.order.client.ClientAdaptersTest.assertCode;
import static com.sesac.gojigi.order.order.client.ClientAdaptersTest.rejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

/**
 * 서킷 · 재시도 AOP 를 실제로 거쳐 application.yml 설정대로 GET 이 한 번 더 불리는지 (A-경-3).
 * 재시도가 서킷 바깥이면 서킷 폴백이 먼저 502(BusinessException)로 바꿔 재시도가 0회가 된다.
 */
@SpringBootTest(classes = UserClientAdapter.class)
@ImportAutoConfiguration({ AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class,
        RetryAutoConfiguration.class })
class ClientRetryTest {

    @MockitoBean UserClient userClient;
    @Autowired UserClientAdapter adapter;

    @Test
    @DisplayName("GET 이 연결 실패면 한 번 더 부른다")
    void getUser_retriesOnFailure() {
        given(userClient.getUser(4L)).willThrow(new ResourceAccessException("timeout"))
                .willReturn(ApiResponse.success("ok", new UserSummaryResponse(4L, "a@b.c", "김", null)));

        assertThat(adapter.getUser(4L).email()).isEqualTo("a@b.c");
        verify(userClient, times(2)).getUser(4L);
    }

    @Test
    @DisplayName("4xx 는 다시 부르지 않는다")
    void getUser_doesNotRetryRejection() {
        given(userClient.getUser(5L)).willThrow(rejected(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        assertCode(() -> adapter.getUser(5L), OrderErrorCode.ORDER_BUYER_NOT_FOUND);
        verify(userClient, times(1)).getUser(5L);
    }
}
