package com.sesac.gojigi.admin.notification.realtime;

/**
 * 새 알림을 그 받는 쪽을 듣는 화면에 보낸다 — 화면이 어느 파드에 연결돼 있든.
 * redis(기본): Pub/Sub 채널 notify:{받는 쪽} → 모든 파드가 받아 자기 연결에 전달 / local: 이 파드 연결에만(테스트 · 로컬)
 */
public interface NotificationBroadcaster {

    /** json = NotificationResponse 직렬화 값 */
    void publish(String recipient, String json);
}
