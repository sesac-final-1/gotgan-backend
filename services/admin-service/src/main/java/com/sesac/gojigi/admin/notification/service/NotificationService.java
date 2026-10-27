package com.sesac.gojigi.admin.notification.service;

import com.sesac.gojigi.admin.notification.dto.InternalNotificationRequest;
import com.sesac.gojigi.admin.notification.dto.NewNotification;
import com.sesac.gojigi.admin.notification.dto.NotificationPage;
import com.sesac.gojigi.admin.notification.dto.NotificationResponse;
import com.sesac.gojigi.admin.notification.exception.NotificationErrorCode;
import com.sesac.gojigi.admin.notification.exception.NotificationException;
import com.sesac.gojigi.admin.notification.realtime.NotificationBroadcaster;
import com.sesac.gojigi.admin.notification.realtime.NotificationStreams;
import com.sesac.gojigi.admin.notification.repository.InboxRepository;
import com.sesac.gojigi.admin.notification.repository.InboxRepository.Item;
import com.sesac.gojigi.common.web.auth.AuthUser;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 알림 센터 (NTF-08) — 쌓기(이벤트 · 내부 요청) → 알림함 저장 → 새 알림이면 실시간 발행. 화면은 자기 받는 쪽(회원 · 스토어 · 관리자)만 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int MAX_PAGE = 50;

    private final InboxRepository inbox;
    private final NotificationMapper mapper;
    private final NotificationBroadcaster broadcaster;
    private final NotificationStreams streams;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /** 업무 이벤트 봉투 하나 — 알림을 만들지 않는 이벤트면 아무것도 안 함. 저장 실패는 던져서 SQS 재전달 */
    public void receiveEvent(String envelopeJson) {
        mapper.map(jsonMapper.readTree(envelopeJson)).forEach(this::deliver);
    }

    /** 다른 서비스 요청 (채팅 · 신고 처리 · 시스템 알림) — sourceId 가 같으면 한 번만 쌓임. 받는 쪽 수를 돌려준다 */
    public int createInternal(InternalNotificationRequest r) {
        List<String> recipients = Stream.of(
                        r.userIds() == null ? Stream.<String>empty() : r.userIds().stream().map(id -> "USER#" + id),
                        r.storeIds() == null ? Stream.<String>empty() : r.storeIds().stream().map(id -> "STORE#" + id),
                        Boolean.TRUE.equals(r.admins()) ? Stream.of("ADMIN") : Stream.<String>empty())
                .flatMap(s -> s).distinct().toList();
        if (recipients.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_RECIPIENT_REQUIRED);
        }
        String sourceId = r.sourceId() == null || r.sourceId().isBlank() ? UUID.randomUUID().toString() : r.sourceId();
        recipients.forEach(to -> deliver(new NewNotification(to, sourceId, r.type(), r.title(), r.body(), r.link(), clock.instant())));
        return recipients.size();
    }

    /** 최신순 목록 — 받는 쪽마다 size 개씩 읽어 합친 뒤 size 개. cursor = 이전 페이지 마지막 알림 id */
    public NotificationPage page(AuthUser user, String cursor, int size) {
        int limit = Math.clamp(size, 1, MAX_PAGE);
        String before = cursor == null || cursor.isBlank() ? null : decode(cursor)[1];
        List<Item> merged = new ArrayList<>();
        recipientsOf(user).forEach(r -> merged.addAll(inbox.latest(r, before, limit)));
        merged.sort(Comparator.comparing(Item::sortKey).reversed());
        List<Item> page = merged.stream().limit(limit).toList();
        String next = merged.size() > limit ? encode(page.getLast().recipient(), page.getLast().sortKey()) : null;
        return new NotificationPage(page.stream().map(NotificationService::toResponse).toList(), next, unreadCount(user));
    }

    public long unreadCount(AuthUser user) {
        return recipientsOf(user).stream().mapToLong(inbox::countUnread).sum();
    }

    /** 읽음 — 내 받는 쪽 알림이 아니거나 없으면 404 (남의 알림이 있는지 알려 주지 않음) */
    public void read(AuthUser user, String id) {
        String[] key = decode(id);
        if (!recipientsOf(user).contains(key[0]) || !inbox.markRead(key[0], key[1])) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    /** 모두 읽음 — 읽음으로 바꾼 수 */
    public int readAll(AuthUser user) {
        int count = 0;
        for (String r : recipientsOf(user)) {
            for (String sk : inbox.unreadKeys(r)) {
                count += inbox.markRead(r, sk) ? 1 : 0;
            }
        }
        return count;
    }

    public SseEmitter stream(AuthUser user) {
        return streams.open(recipientsOf(user));
    }

    /** 로그인 사용자가 받는 쪽 — 회원 + (판매자면 스토어) + (관리자면 관리자 전원) */
    static List<String> recipientsOf(AuthUser user) {
        List<String> keys = new ArrayList<>(List.of("USER#" + user.userId()));
        if (user.hasRole("SELLER") && user.storeId() != null) {
            keys.add("STORE#" + user.storeId());
        }
        if (user.hasRole("ADMIN")) {
            keys.add("ADMIN");
        }
        return keys;
    }

    private void deliver(NewNotification n) {
        if (!inbox.saveIfAbsent(n)) {
            log.info("이미 쌓인 알림 — 건너뜀 recipient={}, sourceId={}", n.recipient(), n.sourceId());
            return;
        }
        NotificationResponse response = new NotificationResponse(encode(n.recipient(), n.sortKey()), n.type(), n.title(), n.body(), n.link(),
                false, n.occurredAt().atZone(KST).toOffsetDateTime());
        broadcaster.publish(n.recipient(), jsonMapper.writeValueAsString(response));
    }

    private static NotificationResponse toResponse(Item i) {
        return new NotificationResponse(encode(i.recipient(), i.sortKey()), i.type(), i.title(), i.body(), i.link(), i.read(),
                i.createdAt().atZone(KST).toOffsetDateTime());
    }

    /** 알림 id · cursor = base64url("받는 쪽|정렬 키") */
    static String encode(String recipient, String sortKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((recipient + "|" + sortKey).getBytes(StandardCharsets.UTF_8));
    }

    static String[] decode(String id) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length == 2) {
                return parts;
            }
        } catch (IllegalArgumentException e) {
            // 형식이 틀린 id — 아래에서 404
        }
        throw new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
    }
}
