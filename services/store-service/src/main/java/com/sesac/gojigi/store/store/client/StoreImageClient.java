package com.sesac.gojigi.store.store.client;

import com.sesac.gojigi.store.common.s3.PresignedPut;
import com.sesac.gojigi.store.common.s3.PresignedUpload;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * images 버킷(공개 읽기) 스토어 대표 이미지 업로드 주소 — presigned PUT (store IRSA s3:PutObject images/stores/*).
 * 서명 · 실패 처리는 입점 서류와 같은 {@link PresignedPut}
 */
@Component
public class StoreImageClient {

    private final S3Presigner s3Presigner;
    private final String bucket;

    public StoreImageClient(S3Presigner s3Presigner, @Value("${app.images.bucket}") String bucket) {
        this.s3Presigner = s3Presigner;
        this.bucket = bucket;
    }

    public PresignedUpload presignUpload(String key, String contentType, long size) {
        return PresignedPut.sign(s3Presigner, bucket, key, contentType, size, "스토어 이미지");
    }
}
