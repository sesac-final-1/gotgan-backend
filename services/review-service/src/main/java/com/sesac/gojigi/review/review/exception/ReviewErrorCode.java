package com.sesac.gojigi.review.review.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** review 도메인 오류 코드 (접두어 REVIEW — docs/api/error-codes.md · review.md) */
@Getter
@RequiredArgsConstructor
public enum ReviewErrorCode implements ErrorCode {
    REVIEW_NOT_ELIGIBLE(HttpStatus.FORBIDDEN, "리뷰를 작성할 수 있는 상품주문이 아닙니다."),
    REVIEW_ALREADY_WRITTEN(HttpStatus.CONFLICT, "이미 리뷰를 작성한 상품주문입니다."),
    REVIEW_WRITE_EXPIRED(HttpStatus.CONFLICT, "리뷰 작성 기한(구매확정 후 30일)이 지났습니다."),
    REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "리뷰를 찾을 수 없습니다."),
    REVIEW_BLINDED(HttpStatus.CONFLICT, "관리자에 의해 블라인드된 리뷰입니다."); // 작성자 수정 · 판매자 답글 모두 막음

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
