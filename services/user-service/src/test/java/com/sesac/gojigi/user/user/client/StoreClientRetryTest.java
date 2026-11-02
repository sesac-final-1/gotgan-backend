package com.sesac.gojigi.user.user.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.user.user.client.dto.StoreResponse;
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
import org.springframework.web.client.ResourceAccessException;

/**
 * 서킷 · 재시도 AOP 를 실제로 거쳐 application.yml 설정대로 동작하는지 — 장애는 한 번 더, 4xx 는 다시 부르지 않음.
 * 재시도가 서킷 바깥이면 폴백이 바꾼 502 까지 다시 불러 4xx 도 두 번 호출된다.
 */
@SpringBootTest(classes = StoreClientAdapter.class)
@ImportAutoConfiguration({ AopAutoConfiguration.class, CircuitBreakerAutoConfiguration.class,
        RetryAutoConfiguration.class })
class StoreClientRetryTest {

    @MockitoBean StoreClient storeClient;
    @Autowired StoreClientAdapter adapter;

    @Test
    @DisplayName("연결 실패면 한 번 더 부른다")
    void getStoreStatus_retriesOnFailure() {
        given(storeClient.getStore(1L)).willThrow(new ResourceAccessException("timeout"))
                .willReturn(ApiResponse.success("ok", new StoreResponse(1L, StoreStatus.CLOSED)));

        assertThat(adapter.getStoreStatus(1L)).isEqualTo(StoreStatus.CLOSED);
        verify(storeClient, times(2)).getStore(1L);
    }

    @Test
    @DisplayName("404 는 다시 부르지 않는다")
    void getStoreStatus_doesNotRetryRejection() {
        given(storeClient.getStore(9L)).willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found",
                HttpHeaders.EMPTY, new byte[0], null));

        assertThatThrownBy(() -> adapter.getStoreStatus(9L)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        verify(storeClient, times(1)).getStore(9L);
    }
}
