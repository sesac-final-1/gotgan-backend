package com.sesac.gojigi.user.user.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.user.user.client.dto.BuyerOrderStatsResponse;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/** 서킷 · 재시도 AOP 를 실제로 거쳐 application.yml 설정("order")대로 동작하는지 — 장애는 한 번 더, 4xx 는 다시 부르지 않음 */
@SpringBootTest(classes = OrderClientAdapter.class)
@ImportAutoConfiguration({ AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class,
        RetryAutoConfiguration.class })
class OrderClientRetryTest {

    @MockitoBean OrderClient orderClient;
    @Autowired OrderClientAdapter adapter;

    @Test
    @DisplayName("5xx 면 한 번 더 부른다")
    void countInProgress_retriesOnFailure() {
        given(orderClient.getBuyerStats(1L)).willThrow(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE,
                        "Unavailable", HttpHeaders.EMPTY, new byte[0], null))
                .willReturn(ApiResponse.success("ok", new BuyerOrderStatsResponse(1L, 0L, 0L)));

        assertThat(adapter.countInProgress(1L)).isZero();
        verify(orderClient, times(2)).getBuyerStats(1L);
    }

    @Test
    @DisplayName("4xx 는 다시 부르지 않는다")
    void countInProgress_doesNotRetryRejection() {
        given(orderClient.getBuyerStats(9L)).willThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                "Bad Request", HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> adapter.countInProgress(9L)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        verify(orderClient, times(1)).getBuyerStats(9L);
    }
}
