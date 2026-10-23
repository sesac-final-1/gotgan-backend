package com.sesac.gojigi.order.claim.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** claim 도메인 에러 코드 (컨벤션 2-5 · docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum ClaimErrorCode implements ErrorCode {
    /** 아직 받지 않는 클레임 유형 — 지금은 결제완료 단계 취소(CANCEL)만 */
    CLAIM_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "아직 지원하지 않는 요청 유형입니다."),
    /** 없는 상품주문 또는 이 주문의 상품주문이 아님 */
    CLAIM_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "주문 상품을 찾을 수 없습니다."),
    /** 결제완료가 아닌 상품주문 · 결제 완료가 아닌 주문 — 즉시 취소할 수 없음 */
    CLAIM_NOT_CANCELLABLE(HttpStatus.CONFLICT, "지금은 취소할 수 없는 상품입니다."),
    /** 이 상품주문에 진행 중인 클레임이 이미 있음 (한 상품주문에 진행 중 클레임 1개 — CLM-06) */
    CLAIM_IN_PROGRESS(HttpStatus.CONFLICT, "이미 처리 중인 요청이 있습니다."),
    /** 지금 클레임 상태에서 할 수 없는 변경 — 서버 버그 또는 동시 처리 */
    CLAIM_INVALID_STATUS(HttpStatus.CONFLICT, "지금 요청 상태에서는 처리할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
