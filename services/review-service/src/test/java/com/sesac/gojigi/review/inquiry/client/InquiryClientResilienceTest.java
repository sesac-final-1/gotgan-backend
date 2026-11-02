package com.sesac.gojigi.review.inquiry.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.review.inquiry.client.dto.ProductSummaryResponse;
import com.sesac.gojigi.review.inquiry.client.dto.UserSummaryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 서킷 · 재시도가 실제 AOP 로 걸리는지 — application.yml 의 resilience4j 설정(서킷 바깥 · 재시도 안쪽)을 그대로 읽는다.
 * 재시도는 장애(연결 실패 · 타임아웃 · 5xx)만 1회, 4xx 변환(BusinessException)은 재시도도 서킷 실패도 아니다.
 */
@SpringBootTest(classes = InquiryClientResilienceTest.Context.class)
class InquiryClientResilienceTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @ImportAutoConfiguration({ CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class })
    @Import({ ProductClientAdapter.class, UserClientAdapter.class })
    static class Context {
    }

    private static final ProductSummaryResponse PRODUCT = new ProductSummaryResponse(101L, 7L, "린넨 오버핏 셔츠", List.of());

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private UserClient userClient;
    @Autowired
    private ProductClientAdapter productAdapter;
    @Autowired
    private UserClientAdapter userAdapter;
    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void resetCircuits() {
        circuitBreakers.circuitBreaker("product").reset();
        circuitBreakers.circuitBreaker("user").reset();
    }

    private static HttpServerErrorException serverError() {
        return HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null);
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("5xx 한 번 뒤 성공 — 재시도로 응답을 돌려준다 (2회 호출)")
    void serverErrorOnce_retriedThenSucceeds() {
        given(productClient.getProduct(101L)).willThrow(serverError()).willReturn(ApiResponse.success("ok", PRODUCT));

        assertThat(productAdapter.getProduct(101L)).isEqualTo(PRODUCT);
        verify(productClient, times(2)).getProduct(101L);
    }

    @Test
    @DisplayName("타임아웃 · 5xx 가 계속 — 재시도 1회 뒤 502, 화면 문구는 상품 조회 실패")
    void failureTwice_returns502AfterRetry() {
        given(productClient.getProduct(101L)).willThrow(new ResourceAccessException("timeout"));
        given(userClient.getUser(4L)).willThrow(serverError());

        Throwable product = catchThrowable(() -> productAdapter.getProduct(101L));
        Throwable user = catchThrowable(() -> userAdapter.getUser(4L));

        assertCode(product, CommonErrorCode.EXTERNAL_API_ERROR);
        assertThat(product).hasMessage("상품 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        assertCode(user, CommonErrorCode.EXTERNAL_API_ERROR);
        assertThat(user).hasMessage("회원 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        verify(productClient, times(2)).getProduct(101L);
        verify(userClient, times(2)).getUser(4L);
        assertThat(circuitBreakers.circuitBreaker("product").getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 상품(404) — 재시도 없이 INQUIRY_PRODUCT_NOT_FOUND, 서킷 실패로 세지 않는다")
    void notFound_notRetriedNotCounted() {
        given(productClient.getProduct(999L)).willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertCode(catchThrowable(() -> productAdapter.getProduct(999L)), InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND);
        verify(productClient, times(1)).getProduct(999L);
        assertThat(circuitBreakers.circuitBreaker("product").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("서킷 OPEN — 호출하지 않고 바로 502")
    void circuitOpen_failsFastWithoutCall() {
        circuitBreakers.circuitBreaker("product").transitionToOpenState();
        circuitBreakers.circuitBreaker("user").transitionToOpenState();

        assertCode(catchThrowable(() -> productAdapter.getProduct(101L)), CommonErrorCode.EXTERNAL_API_ERROR);
        assertCode(catchThrowable(() -> userAdapter.getUser(4L)), CommonErrorCode.EXTERNAL_API_ERROR);
        verify(productClient, never()).getProduct(anyLong());
        verify(userClient, never()).getUser(anyLong());
    }

    @Test
    @DisplayName("장애가 이어지면(최근 5건 이상 50% 실패) 서킷이 열린다")
    void repeatedFailures_openCircuit() {
        given(userClient.getUser(4L)).willThrow(serverError());

        for (int i = 0; i < 5; i++) {
            catchThrowable(() -> userAdapter.getUser(4L));
        }

        assertThat(circuitBreakers.circuitBreaker("user").getState()).isEqualTo(State.OPEN);
        verify(userClient, times(10)).getUser(4L);   // 5건 × (호출 + 재시도 1회)
        assertCode(catchThrowable(() -> userAdapter.getUser(4L)), CommonErrorCode.EXTERNAL_API_ERROR);
        verify(userClient, times(10)).getUser(4L);   // OPEN 뒤에는 부르지 않는다
    }
}
