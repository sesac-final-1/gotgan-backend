package com.sesac.gojigi.admin.user.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PatchExchange;
import tools.jackson.databind.JsonNode;

/**
 * user-service 관리자 회원 내부 API (#78 · #79 · #80) — 응답은 관리자 화면에 그대로 전달하므로 JsonNode 로 받는다.
 * 관리자 X-User-Id 를 모든 호출에 실어 user 로그에 처리자가 남게 한다 (10/23 답변 2-1). 직접 쓰지 않고 UserClientAdapter 를 거친다
 */
@HttpExchange("/internal/users")
public interface UserClient {

    @GetExchange
    ApiResponse<JsonNode> list(@RequestHeader("X-User-Id") Long adminId, @RequestParam(required = false) String q,
                               @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size);

    @GetExchange("/{userId}")
    ApiResponse<JsonNode> detail(@RequestHeader("X-User-Id") Long adminId, @PathVariable Long userId);

    @GetExchange("/{userId}/points")
    ApiResponse<JsonNode> points(@RequestHeader("X-User-Id") Long adminId, @PathVariable Long userId,
                                 @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size);

    @PatchExchange("/{userId}/status")
    ApiResponse<JsonNode> changeStatus(@RequestHeader("X-User-Id") Long adminId, @PathVariable Long userId, @RequestBody JsonNode body);
}
