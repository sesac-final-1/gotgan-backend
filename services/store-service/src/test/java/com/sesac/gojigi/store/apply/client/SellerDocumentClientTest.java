package com.sesac.gojigi.store.apply.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.FileMeta;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

@ExtendWith(MockitoExtension.class)
class SellerDocumentClientTest {

    @Mock
    private S3Client s3Client;

    private SellerDocumentClient sellerDocumentClient;

    @BeforeEach
    void setUp() {
        sellerDocumentClient = new SellerDocumentClient(s3Client, "sesac-gojigi-seller-docs-test");
    }

    @Test
    @DisplayName("파일이 있으면 S3 의 형식 · 크기를 돌려준다")
    void head_fileExists_returnsMeta() {
        // given
        givenHead().willReturn(HeadObjectResponse.builder().contentType("application/pdf").contentLength(1234L).build());

        // when · then
        assertThat(sellerDocumentClient.head("applications/4/a.pdf")).contains(new FileMeta("application/pdf", 1234L));
    }

    @Test
    @DisplayName("NoSuchKey · 404 는 파일 없음(empty)")
    void head_fileMissing_returnsEmpty() {
        // given
        givenHead().willThrow(NoSuchKeyException.builder().build())
                .willThrow(S3Exception.builder().statusCode(404).build());

        // when · then
        assertThat(sellerDocumentClient.head("applications/4/a.pdf")).isEmpty();
        assertThat(sellerDocumentClient.head("applications/4/a.pdf")).isEmpty();
    }

    @Test
    @DisplayName("권한 오류(403) · 연결 실패는 502 EXTERNAL_API_ERROR")
    void head_s3Error_throwsExternalApiError() {
        // given
        givenHead().willThrow(S3Exception.builder().statusCode(403).build())
                .willThrow(SdkClientException.create("timeout"));

        // when · then
        assertThatThrownBy(() -> sellerDocumentClient.head("applications/4/a.pdf"))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> sellerDocumentClient.head("applications/4/a.pdf"))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @SuppressWarnings("unchecked")
    private org.mockito.BDDMockito.BDDMyOngoingStubbing<HeadObjectResponse> givenHead() {
        return given(s3Client.headObject(any(Consumer.class)));
    }
}
