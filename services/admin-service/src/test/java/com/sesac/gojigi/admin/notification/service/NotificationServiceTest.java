package com.sesac.gojigi.admin.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.admin.notification.dto.InternalNotificationRequest;
import com.sesac.gojigi.admin.notification.dto.NewNotification;
import com.sesac.gojigi.admin.notification.dto.NotificationPage;
import com.sesac.gojigi.admin.notification.realtime.NotificationBroadcaster;
import com.sesac.gojigi.admin.notification.realtime.NotificationStreams;
import com.sesac.gojigi.admin.notification.repository.InboxRepository;
import com.sesac.gojigi.admin.notification.repository.InboxRepository.Item;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** 알림 센터 — 받는 쪽 계산 · 멱등 저장 · 실시간 발행 · 목록 합치기 · 남의 알림 차단 */
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T01:00:00Z");
    private final InboxRepository inbox = mock(InboxRepository.class);
    private final NotificationBroadcaster broadcaster = mock(NotificationBroadcaster.class);
    private final NotificationService service = new NotificationService(inbox, new NotificationMapper(), broadcaster,
            mock(NotificationStreams.class), JsonMapper.builder().build(), Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    private static final AuthUser BUYER = new AuthUser(4L, List.of("BUYER"), null);
    private static final AuthUser SELLER = new AuthUser(2L, List.of("BUYER", "SELLER"), 1L);
    private static final AuthUser ADMIN = new AuthUser(1L, List.of("ADMIN"), null);

    private static Item item(String recipient, String sk, boolean read) {
        return new Item(recipient, sk, "ORDER_PAID", "결제가 완료됐어요", null, "/mypage/orders/N1", read, NOW);
    }

    @Test
    @DisplayName("받는 쪽 — 구매자는 회원만, 판매자는 회원 + 스토어, 관리자는 회원 + 관리자 전원")
    void recipients() {
        assertThat(NotificationService.recipientsOf(BUYER)).containsExactly("USER#4");
        assertThat(NotificationService.recipientsOf(SELLER)).containsExactly("USER#2", "STORE#1");
        assertThat(NotificationService.recipientsOf(ADMIN)).containsExactly("USER#1", "ADMIN");
    }

    @Test
    @DisplayName("이벤트 — 새 알림이면 저장 후 그 받는 쪽으로 실시간 발행, 이미 있으면(재전달) 발행하지 않음")
    void receiveEvent_idempotent() {
        String event = """
                {"eventId":"e-1","eventType":"OrderPaid","occurredAt":"2026-10-27T10:00:00+09:00","payload":{"orderNo":"N1","buyerId":4,"payAmt":1000,"items":[]}}""";
        given(inbox.saveIfAbsent(any())).willReturn(true, false);

        service.receiveEvent(event);
        service.receiveEvent(event);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(broadcaster).publish(eq("USER#4"), json.capture());   // 두 번째는 저장 안 됨 → 발행 1번
        assertThat(json.getValue()).contains("\"type\":\"ORDER_PAID\"", "\"read\":false", "\"link\":\"/mypage/orders/N1\"");
    }

    @Test
    @DisplayName("내부 요청 — 회원 · 스토어 · 관리자로 각각 쌓고, 받는 쪽이 없으면 400")
    void createInternal() {
        given(inbox.saveIfAbsent(any())).willReturn(true);

        int n = service.createInternal(new InternalNotificationRequest(List.of(4L), List.of(1L), true, "CHAT_MESSAGE", "새 메시지", "안녕하세요",
                "/mypage/chats/3", "chat-msg-9"));

        assertThat(n).isEqualTo(3);
        ArgumentCaptor<NewNotification> saved = ArgumentCaptor.forClass(NewNotification.class);
        verify(inbox, org.mockito.Mockito.times(3)).saveIfAbsent(saved.capture());
        assertThat(saved.getAllValues()).extracting(NewNotification::recipient).containsExactly("USER#4", "STORE#1", "ADMIN");
        assertThat(saved.getAllValues()).allMatch(x -> x.sortKey().equals(NOW.toEpochMilli() + "#chat-msg-9"));

        assertThatThrownBy(() -> service.createInternal(new InternalNotificationRequest(null, List.of(), false, "X", "t", null, null, null)))
                .isInstanceOf(BusinessException.class).hasMessage("알림 받을 대상을 하나 이상 지정해 주세요.");
    }

    @Test
    @DisplayName("목록 — 판매자는 회원 · 스토어 알림을 합쳐 최신순, 더 있으면 nextCursor, 다음 페이지는 그 이전 것만")
    void page_mergesRecipients() {
        given(inbox.latest(eq("USER#2"), isNull(), eq(2))).willReturn(List.of(item("USER#2", "0000000000300#a", false)));
        given(inbox.latest(eq("STORE#1"), isNull(), eq(2))).willReturn(List.of(item("STORE#1", "0000000000400#b", true), item("STORE#1", "0000000000100#c", false)));
        given(inbox.countUnread(anyString())).willReturn(1L);

        NotificationPage page = service.page(SELLER, null, 2);

        assertThat(page.items()).extracting(r -> NotificationService.decode(r.id())[1]).containsExactly("0000000000400#b", "0000000000300#a");
        assertThat(page.unreadCount()).isEqualTo(2);
        assertThat(NotificationService.decode(page.nextCursor())[1]).isEqualTo("0000000000300#a");

        service.page(SELLER, page.nextCursor(), 2);
        verify(inbox).latest("STORE#1", "0000000000300#a", 2);
    }

    @Test
    @DisplayName("읽음 — 남의 알림 id · 형식이 틀린 id · 없는 알림은 모두 404, 내 알림만 바꿈")
    void read_onlyMine() {
        given(inbox.markRead("USER#4", "sk")).willReturn(true);

        service.read(BUYER, NotificationService.encode("USER#4", "sk"));
        assertThatThrownBy(() -> service.read(BUYER, NotificationService.encode("USER#5", "sk"))).hasMessage("알림을 찾을 수 없습니다.");
        assertThatThrownBy(() -> service.read(BUYER, "%%%")).hasMessage("알림을 찾을 수 없습니다.");
        assertThatThrownBy(() -> service.read(BUYER, NotificationService.encode("USER#4", "none"))).hasMessage("알림을 찾을 수 없습니다.");
        verify(inbox, never()).markRead(eq("USER#5"), anyString());
    }

    @Test
    @DisplayName("모두 읽음 — 내 받는 쪽마다 안 읽은 것만")
    void readAll() {
        given(inbox.unreadKeys("USER#1")).willReturn(List.of("a"));
        given(inbox.unreadKeys("ADMIN")).willReturn(List.of("b", "c"));
        given(inbox.markRead(anyString(), anyString())).willReturn(true);

        assertThat(service.readAll(ADMIN)).isEqualTo(3);
    }
}
