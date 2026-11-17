package com.sesac.gojigi.admin.system.service;

import com.sesac.gojigi.admin.system.dto.response.LoadTestResponses.Visitors;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 현재 접속자 (11/17) — 게이트웨이 ActiveUserFilter 가 요청마다 Redis 정렬 집합에 남긴 "마지막 요청 시각"을 센다.
 * visitors = 회원(u:{userId}) + 비회원(ip:{주소}) 전부, members = 로그인 회원만. 최근 1분 안에 요청한 사람만(화면은 5초마다 다시 센다). 쇼핑몰 탭은 20초마다 접속 신호, 나갈 때 leave 를 보내 바로 빠진다.
 * 부하테스트 요청(X-Gotgan-Loadtest 헤더)은 게이트웨이가 기록하지 않는다. Redis 실패면 null(화면은 '-')
 */
@Slf4j
@Service
public class VisitorService {

    public static final String VISITORS_KEY = "gotgan:active:visitors";
    public static final String MEMBERS_KEY = "gotgan:active:members";
    /** 역할별 로그인 회원 — 게이트웨이가 대표 역할(관리자 > 판매자 > 구매자) 하나에만 기록 */
    public static final String ROLE_KEY_PREFIX = "gotgan:active:role:";
    static final int WINDOW_SECONDS = 60;

    private final StringRedisTemplate redis;
    private final Clock clock;

    public VisitorService(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public Visitors visitors() {
        long since = clock.instant().getEpochSecond() - WINDOW_SECONDS;
        return new Visitors(count(VISITORS_KEY, since), count(MEMBERS_KEY, since), count(ROLE_KEY_PREFIX + "BUYER", since),
                count(ROLE_KEY_PREFIX + "SELLER", since), count(ROLE_KEY_PREFIX + "ADMIN", since), WINDOW_SECONDS);
    }

    /** 오래된 항목을 지우고 남은 수 — 집합이 계속 커지지 않게 읽을 때 정리한다 */
    private Long count(String key, long since) {
        try {
            redis.opsForZSet().removeRangeByScore(key, 0, since);
            return redis.opsForZSet().zCard(key);
        } catch (RuntimeException e) {
            log.warn("접속자 조회 실패 - key={}, {}", key, e.getMessage());
            return null;
        }
    }
}
