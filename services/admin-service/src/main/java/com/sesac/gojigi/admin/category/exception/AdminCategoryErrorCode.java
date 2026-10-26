package com.sesac.gojigi.admin.category.exception;

import com.sesac.gojigi.common.web.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** admin 카테고리 조합 오류 (접두어 CATEGORY — docs/api/error-codes.md). 수수료 없는 상위 카테고리를 켜지 않게 (10/20 A안) */
@Getter
@RequiredArgsConstructor
public enum AdminCategoryErrorCode implements ErrorCode {
    CATEGORY_COMMISSION_RATE_REQUIRED(HttpStatus.BAD_REQUEST, "상위 카테고리는 판매수수료율을 함께 입력해 주세요."),
    CATEGORY_COMMISSION_REQUIRED(HttpStatus.CONFLICT, "판매수수료율이 등록되지 않아 상위 카테고리를 사용할 수 없습니다."),
    CATEGORY_ACTIVATION_PENDING(HttpStatus.BAD_GATEWAY, "카테고리는 만들었지만 사용 처리하지 못했습니다. 수수료를 확인한 뒤 사용으로 바꿔 주세요.");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name(); // 코드 문자열 = enum 상수명
    }
}
