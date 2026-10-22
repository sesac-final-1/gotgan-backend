package com.sesac.gojigi.user.address.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AddressErrorCode implements ErrorCode {
    /** 등록 — 회원당 최대 개수(AddressService.MAX_ADDRESSES) 초과 */
    ADDRESS_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "배송지는 최대 5개까지 등록할 수 있습니다."),
    /** 수정 · 삭제 · 기본 지정 — 없는 · 삭제된 · 다른 회원의 배송지 (다른 회원 배송지의 존재 여부는 알려 주지 않는다) */
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "배송지를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
