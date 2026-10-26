package com.sesac.gojigi.store.apply.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * 입점 서류 버킷(비공개 seller-docs) — 업로드 주소 발급(presigned PUT)과,
 * 업로드된 파일의 형식 · 크기를 S3 에서 직접 확인(HeadObject)한다 (클라이언트 값을 믿지 않음).
 */
@Slf4j
@Component
public class SellerDocumentClient {

    static final Duration UPLOAD_TTL = Duration.ofMinutes(10);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public SellerDocumentClient(S3Client s3Client, S3Presigner s3Presigner, @Value("${app.seller-docs.bucket}") String bucket) {
        this.s3Client = s3Client;
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
            log.error("입점 서류 업로드 주소 발급 실패 - key={}", key, e);
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "업로드 주소를 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 파일이 없으면 empty, S3 장애 · 권한 오류는 EXTERNAL_API_ERROR(502) */
    public Optional<FileMeta> head(String key) {
        try {
            HeadObjectResponse response = s3Client.headObject(b -> b.bucket(bucket).key(key));
            return Optional.of(new FileMeta(response.contentType(), response.contentLength()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {   // HeadObject 는 본문이 없어 NoSuchKey 대신 404 S3Exception 으로 올 때가 많다
                return Optional.empty();
            }
            log.error("입점 서류 확인 실패 - key={}, status={}", key, e.statusCode(), e);
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "서류 저장소에 접근하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (SdkException e) {
            log.error("입점 서류 확인 실패 - key={}", key, e);
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "서류 저장소에 접근하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    public record FileMeta(String contentType, long size) {
    }

    public record PresignedUpload(String url, Instant expiresAt) {
    }
}
