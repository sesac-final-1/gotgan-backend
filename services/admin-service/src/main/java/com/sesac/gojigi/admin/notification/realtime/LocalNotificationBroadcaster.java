package com.sesac.gojigi.admin.notification.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 파드 1개용 — 이 파드에 연결된 화면에만 바로 (app.notification.realtime=local, 테스트 · 로컬) */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.realtime", havingValue = "local")
public class LocalNotificationBroadcaster implements NotificationBroadcaster {

    private final NotificationStreams streams;

    @Override
    public void publish(String recipient, String json) {
        streams.deliver(recipient, json);
    }
}
