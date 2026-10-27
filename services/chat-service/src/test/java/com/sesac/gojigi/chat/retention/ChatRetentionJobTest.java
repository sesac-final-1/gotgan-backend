package com.sesac.gojigi.chat.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.chat.TestcontainersConfiguration;
import com.sesac.gojigi.chat.room.repository.ChatMessageRepository;
import com.sesac.gojigi.chat.room.repository.ChatReadRepository;
import com.sesac.gojigi.chat.room.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 90일 보관 삭제 (chat.md 8-1절) — 시각 고정 2026-10-27 09:10 KST, 기준 = 2026-07-29 09:10.
 * 묶음 2건 · 최대 3묶음으로 줄여 "하루 몫만 지우고 다음 날 이어서"를 확인. ChatRoomApiTest 와 같은 컨텍스트(컨테이너 1개)를 쓴다
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ChatRetentionJobTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T00:10:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String OLD = "2026-07-01 10:00:00";
    private static final String RECENT = "2026-10-01 10:00:00";

    @Autowired JdbcTemplate jdbc;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatRoomRepository roomRepository;
    @Autowired ChatReadRepository readRepository;

    private ChatRetentionJob job;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM chat_read");
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM chat_room");
        job = new ChatRetentionJob(messageRepository, roomRepository, readRepository, CLOCK, 90, 2, 3, Duration.ZERO);
    }

    @Test
    @DisplayName("90일 지난 메시지만 묶음으로 지우고, 하루 최대 묶음을 넘으면 남겨 뒀다가 다음 실행에 이어서 지운다")
    void deletesOldMessagesInBatches() {
        long oldRoom = room(1, OLD);
        long activeRoom = room(2, RECENT);
        for (int i = 0; i < 7; i++) {
            message(oldRoom, OLD);
        }
        message(activeRoom, OLD);
        message(activeRoom, RECENT);
        jdbc.update("INSERT INTO chat_read (chat_room_id, reader_key, last_read_message_id) VALUES (?, 'USER#1', 1)", oldRoom);

        ChatRetentionJob.Result first = job.purge();

        assertThat(first.messages()).isEqualTo(6);   // 3묶음 × 2건 — 오래된 8건 중 6건
        assertThat(first.rooms()).isZero();          // 아직 메시지가 남은 방은 그대로
        assertThat(count("chat_message")).isEqualTo(3);

        ChatRetentionJob.Result second = job.purge();

        assertThat(second.messages()).isEqualTo(2);
        assertThat(second.rooms()).isEqualTo(1);     // 메시지가 다 지워진 오래된 방 + 그 방의 읽음 위치
        assertThat(count("chat_room")).isEqualTo(1);
        assertThat(count("chat_read")).isZero();
        assertThat(jdbc.queryForObject("SELECT created_at FROM chat_message", String.class)).startsWith("2026-10-01");
    }

    @Test
    @DisplayName("지울 것이 없으면 아무것도 지우지 않는다 — 최근 방 · 메시지가 없는 새 방은 그대로")
    void keepsRecentData() {
        message(room(3, RECENT), RECENT);
        jdbc.update("INSERT INTO chat_room (type, buyer_id, store_id) VALUES ('BUYER_SUPPORT', 4, 0)");

        ChatRetentionJob.Result result = job.purge();

        assertThat(result.messages()).isZero();
        assertThat(result.rooms()).isZero();
        assertThat(count("chat_room")).isEqualTo(2);
    }

    private long room(long buyerId, String lastMessageAt) {
        jdbc.update("INSERT INTO chat_room (type, buyer_id, store_id, last_message_at, created_at) VALUES ('STORE_INQUIRY', ?, 5, ?, ?)",
                buyerId, lastMessageAt, lastMessageAt);
        return jdbc.queryForObject("SELECT chat_room_id FROM chat_room WHERE buyer_id = ?", Long.class, buyerId);
    }

    private void message(long roomId, String createdAt) {
        jdbc.update("INSERT INTO chat_message (chat_room_id, sender_id, sender_role, kind, content, created_at) VALUES (?, 1, 'BUYER', 'TEXT', '안녕하세요', ?)",
                roomId, createdAt);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
