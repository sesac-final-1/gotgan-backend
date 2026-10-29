package com.sesac.gojigi.store.common.s3;

import java.time.Instant;

/** 업로드 주소(presigned PUT)와 만료 시각 */
public record PresignedUpload(String url, Instant expiresAt) {
}
