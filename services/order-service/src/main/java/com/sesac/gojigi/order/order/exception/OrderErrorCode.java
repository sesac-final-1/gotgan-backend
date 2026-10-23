package com.sesac.gojigi.order.order.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** order 도메인 에러 코드 (컨벤션 2-5 · docs/api/error-codes.md) */
@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    /** 판매중지 · 차단 · 삭제된 상품 또는 없는 옵션 — product 조회 · 재고 예약 결과 */
    ORDER_PRODUCT_NOT_PURCHASABLE(HttpStatus.CONFLICT, "현재 구매할 수 없는 상품이 있어요."),
    /** 재고 예약 실패 (product 409 STOCK_INSUFFICIENT) */
    ORDER_OUT_OF_STOCK(HttpStatus.CONFLICT, "재고가 부족한 상품이 있어요."),
    /** 포인트 사용 실패 (user 409 POINT_INSUFFICIENT) */
    ORDER_POINT_INSUFFICIENT(HttpStatus.CONFLICT, "보유 포인트가 부족합니다."),
    /** 사용 포인트가 상품 금액 + 배송비보다 큼 */
    ORDER_POINT_EXCEEDS_AMOUNT(HttpStatus.BAD_REQUEST, "사용 포인트가 주문 금액보다 많습니다."),
    /** 주문자 회원 없음 · 탈퇴 (user 404) */
    ORDER_BUYER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원 정보를 찾을 수 없습니다."),
    /** 없는 주문번호 또는 다른 회원의 주문 — 남의 주문이 있다는 것도 알리지 않도록 같은 404 */
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "주문을 찾을 수 없습니다."),
    /** 결제 확인 — 포트원에서 아직 결제 전(결제창 진행 중 · 닫음, payment 409 PAYMENT_NOT_COMPLETED). 주문은 결제 대기 그대로 */
    ORDER_PAYMENT_NOT_COMPLETED(HttpStatus.CONFLICT, "결제가 아직 완료되지 않았어요."),
    /** 결제 확인 — 금액 불일치 · 끝난 결제 · 결제 없음. 재고 · 포인트를 되돌리고 주문 FAILED */
    ORDER_PAYMENT_FAILED(HttpStatus.CONFLICT, "결제를 확인하지 못해 주문이 취소되었어요."),
    /** 지금 주문 상태에서 할 수 없는 변경 — 서버 버그 또는 동시 처리 */
    ORDER_INVALID_STATUS(HttpStatus.CONFLICT, "지금 주문 상태에서는 처리할 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
