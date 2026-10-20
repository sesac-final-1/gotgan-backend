package com.sesac.gojigi.settlement.settlement.dto.response;

import java.time.OffsetDateTime;

/** 정산 명세서 다운로드 링크 — presigned GET 10분, 클릭할 때마다 발급 (STL-10 · IRSA 임시 자격 증명 수명 때문에 길게 줄 수 없음) */
public record ReportUrlResponse(String url, OffsetDateTime expiresAt) {
}
