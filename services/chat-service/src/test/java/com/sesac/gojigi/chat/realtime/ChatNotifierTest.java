package com.sesac.gojigi.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.sesac.gojigi.chat.directory.ChatDirectory;
import com.sesac.gojigi.chat.room.entity.ChatMessage;
import com.sesac.gojigi.chat.room.entity.ChatRoom;
import com.sesac.gojigi.chat.room.entity.ChatRoomType;
import com.sesac.gojigi.chat.room.entity.SenderRole;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 알림 대상 · 문구 · 건너뛰기 (chat.md 7절) — admin 은 가짜 서버, 실행기는 바로 실행 */
class ChatNotifierTest {

    private MockRestServiceServer admin;
    private ChatPresence presence;
    private ChatNotifier notifier;
    private ChatDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://admin");
        admin = MockRestServiceServer.bindTo(builder).build();
        presence = new ChatPresence(null, Clock.systemUTC());
        directory = mock(ChatDirectory.class);
        notifier = new ChatNotifier(builder.build(), presence, Runnable::run, directory);
    }

    @Test
    @DisplayName("구매자 → 스토어 톡: 스토어(STORE#)에게, 가린 구매자 이름 + 내용 40자 · 판매자 화면 링크 · sourceId chat-msg-{id}")
    void buyerToStore() {
        given(directory.userName(10L)).willReturn("김지연");
        admin.expect(requestTo("http://admin/internal/notifications")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"userIds":[],"storeIds":[5],"admins":false,"type":"CHAT_MESSAGE","title":"새 스토어 톡",
                         "body":"김*연: %s…","link":"/seller/chats/7","sourceId":"chat-msg-99"}""".formatted("가".repeat(40))))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        notifier.notifyCounterpart(room(ChatRoomType.STORE_INQUIRY), message(SenderRole.BUYER, "가".repeat(50)));

        admin.verify();
    }

    @Test
    @DisplayName("문구 — 이름이 있으면 이름, 없으면 번호 · 구매자 이름 못 받으면 구매자")
    void titlesWithNames() {
        given(directory.storeName(5L)).willReturn("리넨하우스");
        admin.expect(requestTo("http://admin/internal/notifications")).andExpect(content().json("""
                {"title":"리넨하우스","body":"답변드려요"}""")).andRespond(withStatus(HttpStatus.ACCEPTED));
        admin.expect(requestTo("http://admin/internal/notifications")).andExpect(content().json("""
                {"title":"[고객 상담] 회원 #10","admins":true}""")).andRespond(withStatus(HttpStatus.ACCEPTED));
        admin.expect(requestTo("http://admin/internal/notifications")).andExpect(content().json("""
                {"title":"[판매자 상담] 리넨하우스"}""")).andRespond(withStatus(HttpStatus.ACCEPTED));
        admin.expect(requestTo("http://admin/internal/notifications")).andExpect(content().json("""
                {"title":"곳간 운영팀","storeIds":[5]}""")).andRespond(withStatus(HttpStatus.ACCEPTED));
        admin.expect(requestTo("http://admin/internal/notifications")).andExpect(content().json("""
                {"title":"새 스토어 톡","body":"구매자: 짧은 글"}""")).andRespond(withStatus(HttpStatus.ACCEPTED));

        notifier.notifyCounterpart(room(ChatRoomType.STORE_INQUIRY), message(SenderRole.SELLER, "답변드려요"));
        notifier.notifyCounterpart(room(ChatRoomType.BUYER_SUPPORT), message(SenderRole.BUYER, "도와주세요"));
        notifier.notifyCounterpart(room(ChatRoomType.SELLER_SUPPORT), message(SenderRole.SELLER, "정산 문의"));
        notifier.notifyCounterpart(room(ChatRoomType.SELLER_SUPPORT), message(SenderRole.ADMIN, "확인했어요"));
        given(directory.storeName(5L)).willReturn(null);
        notifier.notifyCounterpart(room(ChatRoomType.STORE_INQUIRY), message(SenderRole.BUYER, "짧은 글"));

        admin.verify();
    }

    @Test
    @DisplayName("받는 쪽이 그 방을 보고 있으면 보내지 않는다")
    void skipWhenWatching() {
        presence.enter("s1", "sub", 7L, "USER#10");
        admin.expect(never(), requestTo("http://admin/internal/notifications"));

        notifier.notifyCounterpart(room(ChatRoomType.STORE_INQUIRY), message(SenderRole.SELLER, "답변드려요"));

        admin.verify();
    }

    @Test
    @DisplayName("admin 오류 · 대기열 가득 참은 알림만 빠지고 예외가 나가지 않는다")
    void failuresAreSwallowed() {
        admin.expect(requestTo("http://admin/internal/notifications")).andRespond(withServerError());
        notifier.notifyCounterpart(room(ChatRoomType.BUYER_SUPPORT), message(SenderRole.BUYER, "도와주세요"));
        admin.verify();

        Executor full = task -> {
            throw new RejectedExecutionException("full");
        };
        new ChatNotifier(RestClient.create(), presence, full, directory)
                .notifyCounterpart(room(ChatRoomType.BUYER_SUPPORT), message(SenderRole.BUYER, "도와주세요"));
    }

    @Test
    @DisplayName("받는 쪽 표 — 구매자/판매자 → 상담방은 관리자, 관리자 → 판매자 상담은 스토어 · 고객 상담은 회원")
    void targets() {
        assertThat(ChatNotifier.target(room(ChatRoomType.STORE_INQUIRY), SenderRole.SELLER).readerKey()).isEqualTo("USER#10");
        assertThat(ChatNotifier.target(room(ChatRoomType.BUYER_SUPPORT), SenderRole.BUYER).readerKey()).isEqualTo("ADMIN");
        assertThat(ChatNotifier.target(room(ChatRoomType.SELLER_SUPPORT), SenderRole.SELLER).admins()).isTrue();
        assertThat(ChatNotifier.target(room(ChatRoomType.SELLER_SUPPORT), SenderRole.ADMIN).readerKey()).isEqualTo("STORE#5");
        assertThat(ChatNotifier.target(room(ChatRoomType.BUYER_SUPPORT), SenderRole.ADMIN).linkPrefix()).isEqualTo("/mypage/chats/");
    }

    private static ChatRoom room(ChatRoomType type) {
        ChatRoom room = mock(ChatRoom.class);
        given(room.getId()).willReturn(7L);
        given(room.getType()).willReturn(type);
        given(room.getBuyerId()).willReturn(10L);
        given(room.getStoreId()).willReturn(5L);
        return room;
    }

    private static ChatMessage message(SenderRole role, String content) {
        ChatMessage message = mock(ChatMessage.class);
        given(message.getId()).willReturn(99L);
        given(message.getSenderRole()).willReturn(role);
        given(message.getContent()).willReturn(content);
        return message;
    }
}
