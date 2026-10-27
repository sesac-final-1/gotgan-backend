package com.sesac.gojigi.admin.notification.controller;

import com.sesac.gojigi.admin.notification.dto.InternalNotificationRequest;
import com.sesac.gojigi.admin.notification.service.NotificationService;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 다른 서비스 → 알림 센터 (클러스터 내부 전용 — 게이트웨이 라우트 없음). 채팅(상대가 접속해 있지 않을 때) · 시스템 알림 등 */
@Tag(name = "알림 센터 (내부)", description = "다른 서비스가 호출 — 인가: 내부")
@RestController
@RequestMapping("/internal/notifications")
@RequiredArgsConstructor
public class InternalNotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "알림 보내기", description = """
            {userIds? · storeIds? · admins?, type, title, body?, link?, sourceId?} → 202 {recipients}. 받는 쪽마다 알림함에 쌓고 연결된 화면에 바로 보냄.
            sourceId 가 같으면 한 번만(재시도 멱등). 에러: 400 NOTIFICATION_RECIPIENT_REQUIRED · INVALID_INPUT""")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String, Integer>> create(@Valid @RequestBody InternalNotificationRequest request) {
        return ApiResponse.success("알림을 보냈습니다.", Map.of("recipients", notificationService.createInternal(request)));
    }
}
