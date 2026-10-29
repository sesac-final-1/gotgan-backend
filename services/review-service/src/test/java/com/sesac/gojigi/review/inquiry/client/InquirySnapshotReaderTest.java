package com.sesac.gojigi.review.inquiry.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader.InquirySnapshot;
import com.sesac.gojigi.review.inquiry.client.dto.ProductSummaryResponse;
import com.sesac.gojigi.review.inquiry.client.dto.UserSummaryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class InquirySnapshotReaderTest {

    @Mock
    private ProductClient productClient;

    @Mock
    private UserClient userClient;

    private InquirySnapshotReader reader;

    @BeforeEach
    void setUp() {
        reader = new InquirySnapshotReader(productClient, userClient);
    }

    @Test
    @DisplayName("상품의 스토어 · 이름 · 첫 사진과 회원 표시 이름을 모은다")
    void read_collectsProductAndWriter() {
        // given
        given(productClient.getProduct(101L)).willReturn(ApiResponse.success("ok",
                new ProductSummaryResponse(101L, 7L, "린넨 오버핏 셔츠", List.of("https://cdn/p1.jpg", "https://cdn/p2.jpg"))));
        given(userClient.getUser(4L)).willReturn(ApiResponse.success("ok", new UserSummaryResponse(4L, "홍길동", "곳간러버")));

        // when
        InquirySnapshot snapshot = reader.read(4L, 101L);

        // then
        assertThat(snapshot).isEqualTo(new InquirySnapshot(7L, "린넨 오버핏 셔츠", "https://cdn/p1.jpg", "곳간러버"));
    }

    @Test
    @DisplayName("사진이 없는 상품은 대표 사진 null")
    void read_productWithoutImages_nullThumbnail() {
        given(productClient.getProduct(101L)).willReturn(ApiResponse.success("ok",
                new ProductSummaryResponse(101L, 7L, "수제 딸기잼", List.of())));
        given(userClient.getUser(4L)).willReturn(ApiResponse.success("ok", new UserSummaryResponse(4L, "홍길동", null)));

        InquirySnapshot snapshot = reader.read(4L, 101L);

        assertThat(snapshot.thumbnailUrl()).isNull();
        assertThat(snapshot.writerDisplayName()).isEqualTo("홍*동");
        assertThat(new ProductSummaryResponse(101L, 7L, "잼", null).thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("없는 · 삭제된 상품(404) — INQUIRY_PRODUCT_NOT_FOUND, 회원은 조회하지 않는다")
    void read_productNotFound_throwsInquiryError() {
        given(productClient.getProduct(101L)).willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThatThrownBy(() -> reader.read(4L, 101L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND));
        verifyNoInteractions(userClient);
    }

    @Test
    @DisplayName("상품 서비스 오류 · 시간 초과 — 502 EXTERNAL_API_ERROR")
    void read_productFailure_throwsExternalError() {
        given(productClient.getProduct(101L))
                .willThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null))
                .willThrow(new ResourceAccessException("timeout"));

        assertExternalError(() -> reader.read(4L, 101L));
        assertExternalError(() -> reader.read(4L, 101L));
    }

    @Test
    @DisplayName("회원 조회 실패 — 502 EXTERNAL_API_ERROR")
    void read_userFailure_throwsExternalError() {
        given(productClient.getProduct(101L)).willReturn(ApiResponse.success("ok",
                new ProductSummaryResponse(101L, 7L, "린넨 오버핏 셔츠", List.of())));
        given(userClient.getUser(4L)).willThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertExternalError(() -> reader.read(4L, 101L));
    }

    private static void assertExternalError(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }
}
