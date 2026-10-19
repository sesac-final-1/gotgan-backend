package com.sesac.gojigi.settlement.settlement.dto.response;

import java.time.OffsetDateTime;

/** 정산 명세서 다운로드 링크 — presigned GET 7일 (STL-10) */
public record ReportUrlResponse(String url, OffsetDateTime expiresAt) {
}
