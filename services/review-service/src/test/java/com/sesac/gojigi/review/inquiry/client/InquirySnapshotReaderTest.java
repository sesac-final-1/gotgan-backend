package com.sesac.gojigi.review.inquiry.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader.InquirySnapshot;
import com.sesac.gojigi.review.inquiry.client.dto.ProductSummaryResponse;
import com.sesac.gojigi.review.inquiry.client.dto.UserSummaryResponse;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InquirySnapshotReaderTest {

    @Mock
    private ProductClientAdapter productClient;

    @Mock
    private UserClientAdapter userClient;

    private InquirySnapshotReader reader;

    @BeforeEach
    void setUp() {
        reader = new InquirySnapshotReader(productClient, userClient);
    }

    @Test
    @DisplayName("상품의 스토어 · 이름 · 첫 사진과 회원 표시 이름을 모은다")
    void read_collectsProductAndWriter() {
        given(productClient.getProduct(101L))
                .willReturn(new ProductSummaryResponse(101L, 7L, "린넨 오버핏 셔츠", List.of("https://cdn/p1.jpg", "https://cdn/p2.jpg")));
        given(userClient.getUser(4L)).willReturn(new UserSummaryResponse(4L, "홍길동", "곳간러버"));

        assertThat(reader.read(4L, 101L)).isEqualTo(new InquirySnapshot(7L, "린넨 오버핏 셔츠", "https://cdn/p1.jpg", "곳간러버"));
    }

    @Test
    @DisplayName("사진이 없는 상품은 대표 사진 null, 닉네임 없으면 가린 실명")
    void read_productWithoutImages_nullThumbnail() {
        given(productClient.getProduct(101L)).willReturn(new ProductSummaryResponse(101L, 7L, "수제 딸기잼", List.of()));
        given(userClient.getUser(4L)).willReturn(new UserSummaryResponse(4L, "홍길동", null));

        InquirySnapshot snapshot = reader.read(4L, 101L);

        assertThat(snapshot.thumbnailUrl()).isNull();
        assertThat(snapshot.writerDisplayName()).isEqualTo("홍*동");
        assertThat(new ProductSummaryResponse(101L, 7L, "잼", null).thumbnailUrl()).isNull();
    }

    @Test
    @DisplayName("없는 상품 — 어댑터 예외 그대로, 회원은 조회하지 않는다")
    void read_productNotFound_skipsUser() {
        given(productClient.getProduct(999L)).willThrow(new InquiryException(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND));

        assertThat(catchThrowable(() -> reader.read(4L, 999L))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND));
        verifyNoInteractions(userClient);
    }

    @Test
    @DisplayName("회원 조회 장애 — 어댑터의 502 그대로")
    void read_userUnavailable_externalError() {
        given(productClient.getProduct(101L)).willReturn(new ProductSummaryResponse(101L, 7L, "린넨 오버핏 셔츠", List.of()));
        given(userClient.getUser(4L)).willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "회원 정보를 불러오지 못했습니다."));

        assertThat(catchThrowable(() -> reader.read(4L, 101L))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR));
    }
}
