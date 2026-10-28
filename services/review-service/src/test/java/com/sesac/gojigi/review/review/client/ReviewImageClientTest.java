package com.sesac.gojigi.review.review.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.review.client.ReviewImageClient.PresignedUpload;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ExtendWith(MockitoExtension.class)
class ReviewImageClientTest {

    private static final String BUCKET = "sesac-gojigi-images-test";

    @Mock
    private S3Presigner failingPresigner;

    /** 서명은 로컬 계산이라 가짜 자격 증명으로 실제 presigner 를 쓴다 (네트워크 없음) */
    private final S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATESTKEY", "test-secret")))
            .build();

    @Test
    @DisplayName("presigned PUT — 버킷 · 키 주소, 10분 만료, Content-Type · Content-Length 가 서명 헤더에 들어간다")
    void presignUpload_validKey_signsTypeAndLength() {
        // given
        ReviewImageClient client = new ReviewImageClient(presigner, BUCKET);
        Instant before = Instant.now();

        // when
        PresignedUpload upload = client.presignUpload("reviews/4/a.jpg", "image/jpeg", 834512L);

        // then
        assertThat(upload.url()).contains(BUCKET).contains("/reviews/4/a.jpg")
                .contains("X-Amz-Expires=600")
                .containsPattern("X-Amz-SignedHeaders=[^&]*content-length")
                .containsPattern("X-Amz-SignedHeaders=[^&]*content-type");
        assertThat(upload.expiresAt()).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
    }

    @Test
    @DisplayName("presign 중 자격 증명 실패는 502 EXTERNAL_API_ERROR")
    @SuppressWarnings("unchecked")
    void presignUpload_credentialFailure_throwsExternalApiError() {
        // given
        given(failingPresigner.presignPutObject(any(Consumer.class))).willThrow(SdkClientException.create("no credentials"));
        ReviewImageClient client = new ReviewImageClient(failingPresigner, BUCKET);

        // when · then
        assertThatThrownBy(() -> client.presignUpload("reviews/4/a.jpg", "image/jpeg", 1L))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }
}
