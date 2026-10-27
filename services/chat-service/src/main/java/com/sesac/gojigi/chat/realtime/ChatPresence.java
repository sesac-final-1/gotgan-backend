package com.sesac.gojigi.chat.realtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 누가 어느 방을 보고 있나 — 보고 있는 쪽에는 알림을 보내지 않는다 (chat.md 7절).
 * 이 파드의 구독은 메모리(세션 → 구독), 파드 간 확인은 Redis ZSET chat:presence:{roomId} (멤버 "{readerKey}|{sessionId}", 점수 = 만료 시각 ms).
 * <ul>
 *   <li>UNSUBSCRIBE · 연결 끊김(SessionDisconnectEvent)이면 바로 지운다</li>
 *   <li>파드가 죽어 지우지 못한 항목은 만료 시각이 지나면 무시 · 정리 — 살아 있는 구독은 {@value #REFRESH_MILLIS}ms 마다 연장</li>
 * </ul>
 * redis 가 null 이면(local 모드) 이 파드 메모리만 본다. Redis 오류면 "보고 있지 않음"으로 — 알림이 빠지는 것보다 한 번 더 가는 쪽
 */
@Slf4j
public class ChatPresence {

    static final String KEY_PREFIX = "chat:presence:";
    static final Duration TTL = Duration.ofSeconds(60);
    static final long REFRESH_MILLIS = 20_000;

    private final Map<String, Map<String, Watch>> sessions = new ConcurrentHashMap<>();   // sessionId → subscriptionId → 방
    private final StringRedisTemplate redis;
    private final Clock clock;

    public ChatPresence(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    void enter(String sessionId, String subscriptionId, Long roomId, String readerKey) {
        Watch watch = new Watch(roomId, readerKey + "|" + sessionId);
        sessions.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>()).put(subscriptionId, watch);
        redisCall(() -> {
            redis.opsForZSet().add(key(roomId), watch.member(), expiry());
            redis.expire(key(roomId), TTL.multipliedBy(2));
        });
    }

    void leave(String sessionId, String subscriptionId) {
        Map<String, Watch> watches = sessions.get(sessionId);
        Watch watch = watches == null ? null : watches.remove(subscriptionId);
        if (watch != null) {
            remove(watch);
        }
    }

    void leaveAll(String sessionId) {
        Map<String, Watch> watches = sessions.remove(sessionId);
        if (watches != null) {
            watches.values().forEach(this::remove);
        }
    }

    public boolean isWatching(Long roomId, String readerKey) {
        String prefix = readerKey + "|";
        if (redis == null) {
            return sessions.values().stream().flatMap(m -> m.values().stream())
                    .anyMatch(w -> w.roomId().equals(roomId) && w.member().startsWith(prefix));
        }
        try {
            Set<String> members = redis.opsForZSet().rangeByScore(key(roomId), clock.millis(), Double.MAX_VALUE);
            return members != null && members.stream().anyMatch(m -> m.startsWith(prefix));
        } catch (RuntimeException e) {
            log.warn("접속 중 확인 실패 — 알림을 보냄 roomId={}, cause={}", roomId, e.toString());
            return false;
        }
    }

    /** 살아 있는 구독 연장 + 만료 항목 정리 — 이 파드 구독 수만큼만(방 전체 스캔 없음) */
    @Scheduled(fixedDelay = REFRESH_MILLIS, initialDelay = REFRESH_MILLIS)
    void refresh() {
        if (redis == null || sessions.isEmpty()) {
            return;
        }
        double expiry = expiry();
        double now = clock.millis();
        redisCall(() -> redis.executePipelined((RedisCallback<Object>) connection -> {
            sessions.values().stream().flatMap(m -> m.values().stream()).forEach(w -> {
                byte[] key = key(w.roomId()).getBytes(UTF_8);
                connection.zSetCommands().zAdd(key, expiry, w.member().getBytes(UTF_8));
                connection.zSetCommands().zRemRangeByScore(key, 0, now);
                connection.keyCommands().expire(key, TTL.multipliedBy(2).toSeconds());
            });
            return null;
        }));
    }

    /** 종료 중인 파드의 구독을 바로 지움 — 연결은 스프링이 닫고 화면은 다른 파드로 다시 연결 */
    @PreDestroy
    void clear() {
        sessions.keySet().forEach(this::leaveAll);
    }

    int localCount() {
        return sessions.values().stream().mapToInt(Map::size).sum();
    }

    private void remove(Watch watch) {
        redisCall(() -> redis.opsForZSet().remove(key(watch.roomId()), watch.member()));
    }

    private double expiry() {
        return clock.millis() + TTL.toMillis();
    }

    private void redisCall(Runnable call) {
        if (redis == null) {
            return;
        }
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("접속 중 표시 갱신 실패 — 만료 시각으로 정리됨, cause={}", e.toString());
        }
    }

    private static String key(Long roomId) {
        return KEY_PREFIX + roomId;
    }

    private record Watch(Long roomId, String member) {
    }
}
