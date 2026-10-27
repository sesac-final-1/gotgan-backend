package com.sesac.gojigi.admin.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.admin.TestcontainersConfiguration;
import com.sesac.gojigi.admin.notification.realtime.NotificationStreams;
import com.sesac.gojigi.admin.notification.repository.InboxRepository;
import com.sesac.gojigi.admin.notification.repository.InboxRepository.Item;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 알림 센터 API — 알림함(DynamoDB)은 가짜, 실시간은 local(이 JVM 연결). 사용자 헤더는 게이트웨이가 넣는 값 그대로.
 * 트레이스 샘플링 0 — LogbackConfigTest 에 Zipkin 경고가 섞이지 않게
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@Import(TestcontainersConfiguration.class)   // admindb(신고) 가 있어 컨텍스트에 DB 가 필요
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired NotificationStreams streams;
    @MockitoBean InboxRepository inbox;

    @Test
    @DisplayName("목록 — 구매자 헤더로 자기 알림 · 안 읽은 수, 로그인 헤더 없으면 401")
    void list() throws Exception {
        given(inbox.latest(anyString(), isNull(), anyInt())).willReturn(List.of(
                new Item("USER#4", "0000000000001#e-1", "ORDER_PAID", "결제가 완료됐어요", "머그 · 28,000원", "/mypage/orders/N1", false,
                        Instant.parse("2026-10-27T01:00:00Z"))));
        given(inbox.countUnread("USER#4")).willReturn(1L);

        mockMvc.perform(get("/api/notifications").header("X-User-Id", 4).header("X-User-Roles", "BUYER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].title").value("결제가 완료됐어요"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-27T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("실시간 — 연결하면 SSE 가 열리고, 내부 요청으로 보낸 알림이 그 연결에 바로 간다")
    void stream_receivesInternalNotification() throws Exception {
        given(inbox.saveIfAbsent(any())).willReturn(true);
        MvcResult sse = mockMvc.perform(get("/api/notifications/stream").header("X-User-Id", 7).header("X-User-Roles", "BUYER"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(sse.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

        mockMvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\":[7],\"type\":\"CHAT_MESSAGE\",\"title\":\"새 메시지\",\"link\":\"/mypage/chats/3\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.recipients").value(1));

        assertThat(sse.getResponse().getContentAsString(StandardCharsets.UTF_8))   // SSE 는 언제나 UTF-8 (브라우저 규격)
                .contains("event:ready")
                .contains("event:notification")
                .contains("\"title\":\"새 메시지\"");
        assertThat(streams.connections()).isPositive();
    }

    @Test
    @DisplayName("내부 요청 — 받는 쪽이 없으면 400 NOTIFICATION_RECIPIENT_REQUIRED, 제목이 없으면 400 INVALID_INPUT")
    void internal_validation() throws Exception {
        mockMvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SYSTEM\",\"title\":\"점검\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_RECIPIENT_REQUIRED"));
        mockMvc.perform(post("/internal/notifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"admins\":true,\"type\":\"SYSTEM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
