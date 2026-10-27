package com.sesac.gojigi.chat.realtime;

/**
 * 방 구독자에게 보낸다 — 구독자가 어느 파드에 연결돼 있든.
 * redis(기본): 채널 chat:room:{roomId} 에 발행 → 모든 파드가 chat:room:* 하나로 구독해 자기 브로커(/sub/rooms/{roomId})로 / local: 이 파드 브로커로 바로
 */
public interface ChatRelay {

    String DESTINATION_PREFIX = "/sub/rooms/";

    /** json = RealtimePayloads 직렬화 값 */
    void publish(Long roomId, String json);
}
