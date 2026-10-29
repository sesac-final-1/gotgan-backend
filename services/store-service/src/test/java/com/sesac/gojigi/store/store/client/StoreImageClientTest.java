package com.sesac.gojigi.store.store.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.common.s3.PresignedUpload;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class StoreImageClientTest {

    private static final String BUCKET = "sesac-gojigi-images-test";

    @Test
    @DisplayName("presigned PUT — images 버킷 · stores/ 키 주소, 10분 만료, Content-Type · Content-Length 가 서명 헤더에 들어간다")
    void presignUpload_signsTypeAndLength() {
        // given — 서명은 로컬 계산이라 가짜 자격 증명으로 실제 presigner 를 쓴다 (네트워크 없음)
        S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIATESTKEY", "test-secret")))
                .build();
        StoreImageClient client = new StoreImageClient(presigner, BUCKET);
        Instant before = Instant.now();

        // when
        PresignedUpload upload = client.presignUpload("stores/1/a.jpg", "image/jpeg", 834512L);

        // then
        // 프론트 CSP 는 리전 가상 호스트 주소만 허용(CLAUDE-PR frontend #35) — path-style · 글로벌 주소면 업로드가 막힘
        assertThat(upload.url()).startsWith("https://" + BUCKET + ".s3.ap-northeast-2.amazonaws.com/stores/1/a.jpg?")
                .contains("X-Amz-Expires=600")
                .containsPattern("X-Amz-SignedHeaders=[^&]*content-length")
                .containsPattern("X-Amz-SignedHeaders=[^&]*content-type");
        assertThat(upload.expiresAt()).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
    }

    @Test
    @DisplayName("자격 증명 실패는 502 EXTERNAL_API_ERROR")
    void presignUpload_credentialFailure_throwsExternalApiError() {
        // given
        S3Presigner failing = mock(S3Presigner.class);
        given(failing.presignPutObject(any(PutObjectPresignRequest.class))).willThrow(SdkClientException.create("no credentials"));

        // when · then
        assertThatThrownBy(() -> new StoreImageClient(failing, BUCKET).presignUpload("stores/1/a.jpg", "image/jpeg", 1L))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }
}
