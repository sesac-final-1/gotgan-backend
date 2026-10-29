package com.sesac.gojigi.store.apply.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.FileMeta;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.PresignedDownload;
import com.sesac.gojigi.store.common.s3.PresignedUpload;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@ExtendWith(MockitoExtension.class)
class SellerDocumentClientTest {

    private static final String BUCKET = "sesac-gojigi-seller-docs-test";

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner failingPresigner;

    /** 서명은 로컬 계산이라 가짜 자격 증명으로 실제 presigner 를 쓴다 (네트워크 없음) */
    private final S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATESTKEY", "test-secret")))
            .build();

    private SellerDocumentClient sellerDocumentClient;

    @BeforeEach
    void setUp() {
        sellerDocumentClient = new SellerDocumentClient(s3Client, presigner, BUCKET);
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

    @Test
    @DisplayName("presigned PUT — 버킷 · 키 주소, 10분 만료, Content-Type · Content-Length 가 서명 헤더에 들어간다")
    void presignUpload_signsTypeAndLength() {
        // given
        Instant before = Instant.now();

        // when
        PresignedUpload upload = sellerDocumentClient.presignUpload("applications/4/a.pdf", "application/pdf", 482113L);

        // then
        assertThat(upload.url()).contains(BUCKET).contains("/applications/4/a.pdf")
                .contains("X-Amz-Expires=600")
                .containsPattern("X-Amz-SignedHeaders=[^&]*content-length")
                .containsPattern("X-Amz-SignedHeaders=[^&]*content-type");
        assertThat(upload.expiresAt()).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
    }

    @Test
    @DisplayName("presign 중 자격 증명 실패는 502 EXTERNAL_API_ERROR")
    void presignUpload_credentialFailure_throwsExternalApiError() {
        // given
        given(failingPresigner.presignPutObject(any(PutObjectPresignRequest.class))).willThrow(SdkClientException.create("no credentials"));
        SellerDocumentClient client = new SellerDocumentClient(s3Client, failingPresigner, BUCKET);

        // when · then
        assertThatThrownBy(() -> client.presignUpload("applications/4/a.pdf", "application/pdf", 1L))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("presigned GET — 버킷 · 키 주소, 10분 만료")
    void presignDownload_signsGetWithTenMinutes() {
        // given
        Instant before = Instant.now();

        // when
        Optional<PresignedDownload> download = sellerDocumentClient.presignDownload("applications/4/a.pdf");

        // then
        assertThat(download).hasValueSatisfying(d -> {
            assertThat(d.url()).contains(BUCKET).contains("/applications/4/a.pdf").contains("X-Amz-Expires=600");
            assertThat(d.expiresAt()).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(5),
                    Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
        });
    }

    @Test
    @DisplayName("열람 주소 발급 중 자격 증명 실패는 예외 대신 empty — 상세 조회를 막지 않는다")
    @SuppressWarnings("unchecked")
    void presignDownload_credentialFailure_returnsEmpty() {
        // given
        given(failingPresigner.presignGetObject(any(Consumer.class))).willThrow(SdkClientException.create("no credentials"));
        SellerDocumentClient client = new SellerDocumentClient(s3Client, failingPresigner, BUCKET);

        // when · then
        assertThat(client.presignDownload("applications/4/a.pdf")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private org.mockito.BDDMockito.BDDMyOngoingStubbing<HeadObjectResponse> givenHead() {
        return given(s3Client.headObject(any(Consumer.class)));
    }
}
