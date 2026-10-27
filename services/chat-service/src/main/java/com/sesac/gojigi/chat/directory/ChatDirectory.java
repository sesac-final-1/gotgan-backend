package com.sesac.gojigi.chat.directory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * 채팅에 보이는 이름 · 스토어 상태 · 정산 카드 — user GET /internal/users/{id} · store GET /internal/stores/{id} (chat.md 1 · 7절, D-1)
 * · settlement GET /internal/settlements/{id}(정산 카드 제목 · 내 스토어 정산인지).
 * 방 목록을 열 때마다 방 수만큼 부르지 않게 짧게 기억한다: 이름은 {@value #NAME_TTL_MINUTES}분, 스토어(정지 · 폐점 반영)는 1분, 조회 실패는 10초.
 * 실패하면 null — 화면은 번호("스토어 #5")로, 스토어 상태는 막지 않는 쪽(fail-open)으로
 */
@Slf4j
@Component
public class ChatDirectory {

    static final long NAME_TTL_MINUTES = 10;
    static final Duration STORE_TTL = Duration.ofMinutes(1);
    static final Duration FAILURE_TTL = Duration.ofSeconds(10);
    static final int MAX_ENTRIES = 10_000;

    private final RestClient userClient;
    private final RestClient storeClient;
    private final RestClient settlementClient;
    private final Clock clock;
    private final Map<Long, Cached<String>> users = new ConcurrentHashMap<>();
    private final Map<Long, Cached<StoreInfo>> stores = new ConcurrentHashMap<>();
    private final Map<Long, Cached<SettlementInfo>> settlements = new ConcurrentHashMap<>();

    public ChatDirectory(@Qualifier("userRestClient") RestClient userClient, @Qualifier("storeRestClient") RestClient storeClient,
            @Qualifier("settlementRestClient") RestClient settlementClient, Clock clock) {
        this.userClient = userClient;
        this.storeClient = storeClient;
        this.settlementClient = settlementClient;
        this.clock = clock;
    }

    /** 회원 이름 — 없거나 조회 실패면 null */
    public String userName(Long userId) {
        return cached(users, userId, Duration.ofMinutes(NAME_TTL_MINUTES), id -> data(userClient, "/internal/users/{id}", id)
                .map(d -> d.path("name").asString(null)).orElse(null));
    }

    /** 스토어 이름 — 없거나 조회 실패면 null */
    public String storeName(Long storeId) {
        StoreInfo info = store(storeId);
        return info == null ? null : info.name();
    }

    /** 새 스토어 톡 · 스토어 톡 전송 가능 여부 (D-1) — 정지 · 폐점 · 없는 스토어면 false, 조회 실패면 true(막지 않음) */
    public boolean storeOpen(Long storeId) {
        StoreInfo info = store(storeId);
        return info == null || info.open();
    }

    /** 정산 카드 — 스토어 · 주차 · 지급액. 없거나 조회 실패면 null (상태가 바뀌어도 이 값들은 그대로라 이름과 같이 10분) */
    public SettlementInfo settlement(Long settlementId) {
        return cached(settlements, settlementId, Duration.ofMinutes(NAME_TTL_MINUTES), id -> data(settlementClient, "/internal/settlements/{id}", id)
                .map(d -> d.path("settlement"))
                .filter(s -> s.has("storeId"))
                .map(s -> new SettlementInfo(s.path("storeId").asLong(), s.path("week").asString(null), s.path("payoutAmt").asLong()))
                .orElse(null));
    }

    /** 판매자에게는 구매자 이름을 가린다 — 김지연 → 김*연, 홍길 → 홍* */
    public static String mask(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        if (name.length() == 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    private StoreInfo store(Long storeId) {
        return cached(stores, storeId, STORE_TTL, id -> {
            try {
                return data(storeClient, "/internal/stores/{id}", id)
                        .map(d -> new StoreInfo(d.path("storeName").asString(null), "ACTIVE".equals(d.path("status").asString(null))))
                        .orElse(null);
            } catch (HttpClientErrorException.NotFound e) {
                return new StoreInfo(null, false);
            }
        });
    }

    /** 응답 봉투의 data — 404 는 호출한 쪽이 다루도록 그대로, 그 밖의 실패는 빈 값 */
    private Optional<JsonNode> data(RestClient client, String uri, Long id) {
        try {
            JsonNode body = client.get().uri(uri, id).retrieve().body(JsonNode.class);
            return Optional.ofNullable(body).map(b -> b.path("data")).filter(d -> !d.isMissingNode() && !d.isNull());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(404))) {
                throw e;
            }
            log.warn("채팅 이름 조회 실패 uri={}, id={}, status={}", uri, id, e.getStatusCode());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("채팅 이름 조회 실패 uri={}, id={}, cause={}", uri, id, e.toString());
            return Optional.empty();
        }
    }

    private <T> T cached(Map<Long, Cached<T>> cache, Long id, Duration ttl, Function<Long, T> loader) {
        if (id == null) {
            return null;
        }
        Instant now = clock.instant();
        Cached<T> hit = cache.get(id);
        if (hit != null && now.isBefore(hit.expiresAt())) {
            return hit.value();
        }
        T value;
        try {
            value = loader.apply(id);
        } catch (HttpClientErrorException.NotFound e) {
            value = null;
        }
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();   // 상한 — 오래된 것부터 고르지 않고 비운다(다시 채우는 비용이 작음)
        }
        cache.put(id, new Cached<>(value, now.plus(value == null ? FAILURE_TTL : ttl)));
        return value;
    }

    record StoreInfo(String name, boolean open) {
    }

    public record SettlementInfo(Long storeId, String week, long payoutAmt) {
    }

    private record Cached<T>(T value, Instant expiresAt) {
    }
}
