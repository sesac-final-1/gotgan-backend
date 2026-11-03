package com.sesac.gojigi.admin.notification.realtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * 알림 SSE 화면이 떠나 연결이 끊기면(화면 이동 · 새로고침 · 탭 닫기) heartbeat 전송이 Broken pipe 로 실패하고
 * 컨테이너가 에러 디스패치를 한다. 공통 GlobalExceptionHandler 가 받으면 ERROR 스택을 남기고, 이미 text/event-stream 인
 * 응답에 ApiResponse(JSON)를 쓰려다 HttpMessageNotWritableException 까지 난다(11/3 QA — 1시간 22건, Q-구-7).
 * 끊김은 정상 흐름이라 공통 처리보다 먼저 받아 DEBUG 로만 남기고 응답은 쓰지 않는다. 연결은 NotificationStreams 가 목록에서 지운다
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class SseDisconnectHandler {

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnected(AsyncRequestNotUsableException e) {
        log.debug("SSE 연결 끊김 - {}", e.getMessage());
    }
}
