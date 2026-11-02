package com.sesac.gojigi.review.inquiry.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.review.inquiry.client.dto.ProductSummaryResponse;
import com.sesac.gojigi.review.inquiry.client.dto.UserSummaryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 어댑터 — 실제 HTTP 응답 파싱 · 4xx 변환. 서킷 · 재시도는 Spring AOP 라 여기서는 폴백을 직접 부른다 (AOP 는 InquiryClientResilienceTest).
 */
class InquiryClientAdaptersTest {

    private MockRestServiceServer server;
    private ProductClientAdapter productAdapter;
    private UserClientAdapter userAdapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        InquiryClientConfig config = new InquiryClientConfig();
        productAdapter = new ProductClientAdapter(config.productClient(builder, "http://product"));
        userAdapter = new UserClientAdapter(config.userClient(builder, "http://user"));
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Test
    @DisplayName("상품 · 회원 — 각 주소로 GET, 봉투 data 의 필요한 값만 읽는다 (모르는 필드 무시)")
    void get_parsesEnvelope() {
        // given
        server.expect(requestTo("http://product/api/products/101")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"상품을 조회했습니다.","data":{"productId":101,"storeId":7,"name":"린넨 오버핏 셔츠",
                         "price":89000,"images":["https://cdn/p1.jpg"],"purchasable":true}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://user/internal/users/4")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"회원 정보를 조회했습니다.","data":{"userId":4,"email":"a@b.c","name":"홍길동","nick":"곳간러버"}}""",
                        MediaType.APPLICATION_JSON));

        // when
        ProductSummaryResponse product = productAdapter.getProduct(101L);
        UserSummaryResponse user = userAdapter.getUser(4L);

        // then
        assertThat(product).isEqualTo(new ProductSummaryResponse(101L, 7L, "린넨 오버핏 셔츠", List.of("https://cdn/p1.jpg")));
        assertThat(user.displayName()).isEqualTo("곳간러버");
        server.verify();
    }

    @Test
    @DisplayName("없는 상품 404 — INQUIRY_PRODUCT_NOT_FOUND")
    void product404_notFound() {
        server.expect(requestTo("http://product/api/products/999")).andRespond(withResourceNotFound());

        assertCode(catchThrowable(() -> productAdapter.getProduct(999L)), InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("그 밖의 4xx — review 쪽 호출 버그라 502 (BusinessException 이라 서킷 실패로 세지 않음)")
    void other4xx_externalError() {
        server.expect(requestTo("http://product/api/products/101")).andRespond(withBadRequest());
        server.expect(requestTo("http://user/internal/users/4")).andRespond(withResourceNotFound());

        assertCode(catchThrowable(() -> productAdapter.getProduct(101L)), CommonErrorCode.EXTERNAL_API_ERROR);
        assertCode(catchThrowable(() -> userAdapter.getUser(4L)), CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("폴백 — 이미 바꾼 예외는 그대로, 서킷 OPEN · 장애는 화면 문구로 502")
    void fallback_keepsBusinessOtherwise502() {
        InquiryException notFound = new InquiryException(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND);
        CallNotPermittedException open = CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("product"));

        assertThat(catchThrowable(() -> productAdapter.getProductFallback(101L, notFound))).isSameAs(notFound);
        Throwable blocked = catchThrowable(() -> productAdapter.getProductFallback(101L, open));
        assertCode(blocked, CommonErrorCode.EXTERNAL_API_ERROR);
        assertThat(blocked).hasMessage("상품 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
        Throwable down = catchThrowable(() -> userAdapter.getUserFallback(4L, new ResourceAccessException("timeout")));
        assertCode(down, CommonErrorCode.EXTERNAL_API_ERROR);
        assertThat(down).hasMessage("회원 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
}
