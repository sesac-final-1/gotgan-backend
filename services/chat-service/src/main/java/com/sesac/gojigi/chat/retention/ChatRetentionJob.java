package com.sesac.gojigi.chat.retention;

import com.sesac.gojigi.chat.room.repository.ChatMessageRepository;
import com.sesac.gojigi.chat.room.repository.ChatReadRepository;
import com.sesac.gojigi.chat.room.repository.ChatRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 메시지 90일 보관 삭제 (D-3, chat.md 8-1절) — 하루 한 번 09:10 KST, 한 파드만(ShedLock).
 * 1,000건씩 나눠 지우고 묶음 사이 200ms 쉼, 하루 최대 50묶음(남으면 다음 날). 끝에 메시지가 다 지워진 오래된 방을 정리
 */
@Slf4j
@Component
public class ChatRetentionJob {

    private final ChatMessageRepository messageRepository;
    private final ChatRoomRepository roomRepository;
    private final ChatReadRepository readRepository;
    private final Clock clock;
    private final int days;
    private final int batchSize;
    private final int maxBatches;
    private final Duration pause;

    public ChatRetentionJob(ChatMessageRepository messageRepository, ChatRoomRepository roomRepository,
            ChatReadRepository readRepository, Clock clock,
            @Value("${app.chat.retention.days}") int days,
            @Value("${app.chat.retention.batch-size}") int batchSize,
            @Value("${app.chat.retention.max-batches}") int maxBatches,
            @Value("${app.chat.retention.pause}") Duration pause) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.readRepository = readRepository;
        this.clock = clock;
        this.days = days;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
        this.pause = pause;
    }

    @Scheduled(cron = "${app.chat.retention.cron}", zone = "Asia/Seoul")
    @SchedulerLock(name = "chatRetention", lockAtMostFor = "10m")
    public void run() {
        purge();
    }

    /** 지운 메시지 · 방 수 — 묶음마다 별도 트랜잭션(잠금을 짧게) */
    public Result purge() {
        long started = clock.millis();
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(days);
        long messages = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int deleted = messageRepository.deleteOlderThan(cutoff, batchSize);
            messages += deleted;
            if (deleted < batchSize || !rest()) {
                break;
            }
        }
        readRepository.deleteOfEmptyRoomsOlderThan(cutoff);
        int rooms = roomRepository.deleteEmptyOlderThan(cutoff);
        log.info("채팅 보관 삭제 — 기준 {} 이전, 메시지 {}건 · 빈 방 {}개, {}ms", cutoff, messages, rooms, clock.millis() - started);
        return new Result(messages, rooms);
    }

    /** 묶음 사이 쉼 — 종료 중 인터럽트면 멈추고 다음 날 이어서 */
    private boolean rest() {
        try {
            Thread.sleep(pause);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public record Result(long messages, int rooms) {
    }
}
