package com.sesac.gojigi.product.image.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** 상품 이미지 버킷(images, products/ 공개 읽기) — 업로드 주소 발급(presigned PUT)만. 조회 URL 은 ImageUrls */
@Slf4j
@Component
public class ProductImageClient {

    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);

    private final S3Presigner s3Presigner;
    private final String bucket;

    public ProductImageClient(S3Presigner s3Presigner, @Value("${app.images.bucket}") String bucket) {
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
    }

    /**
     * presigned PUT — Content-Type · Content-Length 를 서명에 넣어 발급 때 말한 형식 · 크기와 다른 파일은 S3 가 403 으로 막는다.
     * 서명은 로컬 계산이지만 IRSA 자격 증명을 못 얻으면 실패 → EXTERNAL_API_ERROR(502)
     */
    public PresignedUpload presignUpload(String key, String contentType, long size) {
        try {
            PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(b -> b.signatureDuration(UPLOAD_TTL)
                    .putObjectRequest(p -> p.bucket(bucket).key(key).contentType(contentType).contentLength(size)));
            return new PresignedUpload(presigned.url().toString(), presigned.expiration());
        } catch (SdkException e) {
            log.error("상품 이미지 업로드 주소 발급 실패 - key={}", key, e);
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "업로드 주소를 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    public record PresignedUpload(String url, Instant expiresAt) {
    }
}
