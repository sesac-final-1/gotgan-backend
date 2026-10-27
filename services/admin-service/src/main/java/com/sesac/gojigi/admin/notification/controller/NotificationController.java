package com.sesac.gojigi.admin.notification.controller;

import com.sesac.gojigi.admin.notification.dto.NotificationPage;
import com.sesac.gojigi.admin.notification.service.NotificationService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 알림 센터 (NTF-08) — 로그인한 누구나 자기 알림만. 게이트웨이 /api/notifications/** → admin-service */
@Tag(name = "알림 센터", description = "알림함 · 실시간(SSE) — 인가: 로그인 (BUYER · SELLER · ADMIN, 자기 알림만)")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "알림 목록", description = "최신순, nextCursor 가 있으면 ?cursor= 로 이전 알림. 판매자는 회원 + 스토어 알림, 관리자는 + 관리자 전원 알림")
    @GetMapping
    public ApiResponse<NotificationPage> list(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                              @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("알림을 조회했습니다.", notificationService.page(user, cursor, size));
    }

    @Operation(summary = "안 읽은 알림 수", description = "{count} — 헤더 벨 숫자")
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("안 읽은 알림 수를 조회했습니다.", Map.of("count", notificationService.unreadCount(user)));
    }

    @Operation(summary = "읽음", description = "id = 목록의 알림 id. 내 알림이 아니거나 없으면 404 NOTIFICATION_NOT_FOUND")
    @PatchMapping("/{id}/read")
    public ApiResponse<Void> read(@Parameter(hidden = true) @CurrentUser AuthUser user, @PathVariable String id) {
        notificationService.read(user, id);
        return ApiResponse.success("알림을 읽음으로 바꿨습니다.", null);
    }

    @Operation(summary = "모두 읽음", description = "{count} = 읽음으로 바꾼 수")
    @PatchMapping("/read-all")
    public ApiResponse<Map<String, Integer>> readAll(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ApiResponse.success("알림을 모두 읽음으로 바꿨습니다.", Map.of("count", notificationService.readAll(user)));
    }

    @Operation(summary = "실시간 알림 (SSE)", description = """
            text/event-stream — 연결 직후 event: ready, 새 알림마다 event: notification(data = 목록 항목과 같은 JSON), 25초마다 주석(연결 유지).
            브라우저 EventSource 는 Authorization 헤더를 못 보내므로 화면은 fetch 기반 SSE(@microsoft/fetch-event-source)로 연결""")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return notificationService.stream(user);
    }
}
