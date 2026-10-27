package com.sesac.gojigi.chat.realtime;

import com.sesac.gojigi.chat.directory.ChatDirectory;
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
    private final ChatDirectory directory;

    public ChatNotifier(@Qualifier("adminRestClient") RestClient adminClient, ChatPresence presence,
            @Qualifier("chatNotifyExecutor") Executor executor, ChatDirectory directory) {
        this.adminClient = adminClient;
        this.presence = presence;
        this.executor = executor;
        this.directory = directory;
    }

    void notifyCounterpart(ChatRoom room, ChatMessage message) {
        Target target = target(room, message.getSenderRole());
        try {
            executor.execute(() -> send(room, message, target));
        } catch (RejectedExecutionException e) {
            log.warn("채팅 알림 대기열이 가득 차 건너뜀 roomId={}, messageId={}", room.getId(), message.getId());
        }
    }

    /** 알림 스레드에서 — 보고 있는지 확인 · 이름 조회(ChatDirectory, 캐시)까지 여기서 해 전송 경로를 막지 않는다 */
    private void send(ChatRoom room, ChatMessage message, Target target) {
        Long roomId = room.getId();
        if (presence.isWatching(roomId, target.readerKey())) {
            return;
        }
        Request request = target.request(roomId, title(room, message.getSenderRole()), body(room, message), "chat-msg-" + message.getId());
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

    /** 문구 (7절) — 판매자에게 가는 구매자 이름은 가린다. 이름을 못 받으면 번호 */
    private String title(ChatRoom room, SenderRole sender) {
        return switch (sender) {
            case BUYER -> room.getType() == ChatRoomType.STORE_INQUIRY ? "새 스토어 톡" : "[고객 상담] " + userLabel(room.getBuyerId());
            case SELLER -> room.getType() == ChatRoomType.STORE_INQUIRY
                    ? storeLabel(room.getStoreId()) : "[판매자 상담] " + storeLabel(room.getStoreId());
            default -> "곳간 운영팀";
        };
    }

    private String body(ChatRoom room, ChatMessage message) {
        String content = message.getContent().replace('\n', ' ');
        String text = content.length() <= BODY_LENGTH ? content : content.substring(0, BODY_LENGTH) + "…";
        if (message.getSenderRole() == SenderRole.BUYER && room.getType() == ChatRoomType.STORE_INQUIRY) {
            String name = directory.userName(room.getBuyerId());
            return (name == null ? "구매자" : ChatDirectory.mask(name)) + ": " + text;
        }
        return text;
    }

    private String storeLabel(Long storeId) {
        String name = directory.storeName(storeId);
        return name != null ? name : "스토어 #" + storeId;
    }

    private String userLabel(Long userId) {
        String name = directory.userName(userId);
        return name != null ? name : "회원 #" + userId;
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
