package com.sesac.gojigi.settlement.settlement.dto.response;

import java.util.List;

/** 지급 실행 결과 — API 명세 {success[], failed[]} */
public record PayResultResponse(List<Long> success, List<Failure> failed) {

    public record Failure(Long id, String code, String message) {
    }
}
