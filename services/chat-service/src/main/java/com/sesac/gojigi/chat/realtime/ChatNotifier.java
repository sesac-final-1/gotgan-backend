package com.sesac.gojigi.chat.realtime;

import com.sesac.gojigi.chat.room.entity.ChatMessage;
import com.sesac.gojigi.chat.room.entity.ChatRoom;
import com.sesac.gojigi.chat.room.entity.ChatRoomType;
import com.sesac.gojigi.chat.room.entity.SenderRole;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 상대가 그 방을 보고 있지 않으면 알림 센터로 (chat.md 7절, admin POST /internal/notifications).
 * 전송 경로를 막지 않게 작은 전용 스레드에서 보내고, 대기열이 차거나 admin 이 안 되면 알림만 빠진다(메시지는 저장 · 전달됨).
 * sourceId = chat-msg-{messageId} — 재시도해도 알림은 한 번
 */
@Slf4j
@Component
public class ChatNotifier {

    static final String TYPE = "CHAT_MESSAGE";
    private static final int BODY_LENGTH = 40;

    private final RestClient adminClient;
    private final ChatPresence presence;
    private final Executor executor;

    public ChatNotifier(@Qualifier("adminRestClient") RestClient adminClient, ChatPresence presence,
            @Qualifier("chatNotifyExecutor") Executor executor) {
        this.adminClient = adminClient;
        this.presence = presence;
        this.executor = executor;
    }

    void notifyCounterpart(ChatRoom room, ChatMessage message) {
        Target target = target(room, message.getSenderRole());
        Request request = target.request(room.getId(), title(room, message.getSenderRole()), body(message), "chat-msg-" + message.getId());
        try {
            executor.execute(() -> send(room.getId(), target, request));
        } catch (RejectedExecutionException e) {
            log.warn("채팅 알림 대기열이 가득 차 건너뜀 roomId={}, messageId={}", room.getId(), message.getId());
        }
    }

    private void send(Long roomId, Target target, Request request) {
        if (presence.isWatching(roomId, target.readerKey())) {
            return;
        }
        try {
            adminClient.post().uri("/internal/notifications").body(request).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("채팅 알림 전송 실패 — 메시지는 전달됨 roomId={}, sourceId={}, cause={}", roomId, request.sourceId(), e.toString());
        }
    }

    /** 보낸 쪽의 반대편 (7절 표) */
    static Target target(ChatRoom room, SenderRole sender) {
        return switch (sender) {
            case BUYER -> room.getType() == ChatRoomType.STORE_INQUIRY ? Target.store(room.getStoreId()) : Target.admin();
            case SELLER -> room.getType() == ChatRoomType.STORE_INQUIRY ? Target.user(room.getBuyerId()) : Target.admin();
            default -> room.getType() == ChatRoomType.SELLER_SUPPORT ? Target.store(room.getStoreId()) : Target.user(room.getBuyerId());
        };
    }

    private static String title(ChatRoom room, SenderRole sender) {
        return switch (sender) {
            case BUYER -> room.getType() == ChatRoomType.STORE_INQUIRY ? "새 스토어 톡" : "[고객 상담] 새 메시지";
            case SELLER -> room.getType() == ChatRoomType.STORE_INQUIRY ? "스토어 #" + room.getStoreId() + " 답변" : "[판매자 상담] 새 메시지";
            default -> "곳간 운영팀 답변";
        };
    }

    private static String body(ChatMessage message) {
        String content = message.getContent().replace('\n', ' ');
        return content.length() <= BODY_LENGTH ? content : content.substring(0, BODY_LENGTH) + "…";
    }

    /** 알림 받는 쪽 — readerKey 로 접속 중인지 보고, link 는 받는 쪽 화면 */
    record Target(String readerKey, List<Long> userIds, List<Long> storeIds, boolean admins, String linkPrefix) {

        static Target user(Long userId) {
            return new Target("USER#" + userId, List.of(userId), List.of(), false, "/mypage/chats/");
        }

        static Target store(Long storeId) {
            return new Target("STORE#" + storeId, List.of(), List.of(storeId), false, "/seller/chats/");
        }

        static Target admin() {
            return new Target("ADMIN", List.of(), List.of(), true, "/admin/chats/");
        }

        Request request(Long roomId, String title, String body, String sourceId) {
            return new Request(userIds, storeIds, admins, TYPE, title, body, linkPrefix + roomId, sourceId);
        }
    }

    /** admin InternalNotificationRequest 와 같은 모양 */
    record Request(List<Long> userIds, List<Long> storeIds, Boolean admins, String type, String title, String body, String link,
            String sourceId) {
    }
}
