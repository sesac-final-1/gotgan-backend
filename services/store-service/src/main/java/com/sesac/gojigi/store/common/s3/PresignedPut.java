package com.sesac.gojigi.store.common.s3;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * store 의 업로드 주소 발급 공용 — 입점 서류(seller-docs) · 스토어 대표 이미지(images) 가 버킷만 바꿔 같이 쓴다.
 * Content-Type · Content-Length 를 서명에 넣어 발급 때 말한 형식 · 크기와 다른 파일은 S3 가 403 으로 막는다.
 * 서명은 로컬 계산이지만 IRSA 자격 증명을 못 얻으면 실패 → EXTERNAL_API_ERROR(502)
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PresignedPut {

    public static final Duration TTL = Duration.ofMinutes(10);

    /** {@code target} 은 실패 로그에 남길 대상 이름(예: "입점 서류") */
    public static PresignedUpload sign(S3Presigner presigner, String bucket, String key, String contentType, long size, String target) {
        PutObjectRequest object = PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType(contentType).contentLength(size).build();
        try {
            PresignedPutObjectRequest presigned = presigner.presignPutObject(
                    PutObjectPresignRequest.builder().signatureDuration(TTL).putObjectRequest(object).build());
            return new PresignedUpload(presigned.url().toString(), presigned.expiration());
        } catch (SdkException e) {
            log.error("{} 업로드 주소 발급 실패 - key={}", target, key, e);
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "업로드 주소를 발급하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
