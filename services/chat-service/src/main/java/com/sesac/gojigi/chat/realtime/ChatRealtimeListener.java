package com.sesac.gojigi.chat.realtime;

import com.sesac.gojigi.chat.room.entity.MessageKind;
import com.sesac.gojigi.chat.room.service.ChatEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/** 커밋된 일만 구독자에게 — 새 메시지(+ 대화 메시지면 알림) · 읽음 · 상태 (chat.md 6 · 7절) */
@Component
@RequiredArgsConstructor
public class ChatRealtimeListener {

    private final ChatRelay relay;
    private final ChatNotifier notifier;
    private final JsonMapper json;

    @TransactionalEventListener
    public void onMessage(ChatEvents.MessageSaved event) {
        relay.publish(event.room().getId(), json.writeValueAsString(RealtimePayloads.Message.of(event.message())));
        if (event.message().getKind() == MessageKind.TEXT) {
            notifier.notifyCounterpart(event.room(), event.message());
        }
    }

    @TransactionalEventListener
    public void onRead(ChatEvents.ReadMoved event) {
        relay.publish(event.roomId(), json.writeValueAsString(RealtimePayloads.Read.of(event.role(), event.lastMessageId())));
    }

    @TransactionalEventListener
    public void onStatus(ChatEvents.StatusChanged event) {
        relay.publish(event.roomId(), json.writeValueAsString(RealtimePayloads.Status.of(event.status())));
    }
}
