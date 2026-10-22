package com.sesac.gojigi.user.user.scheduler;

import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.sesac.gojigi.user.user.service.UserStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 이용정지 기간 만료 자동 해제 — 1분마다 종료 시각이 지난 기간 정지(7일 · 30일 …)를 해제한다. 영구 정지는 대상 아님.
 * fixedDelay 라 앱이 뜨자마자 한 번 돈다 — 클러스터가 꺼져 있던 밤사이 끝난 정지도 기동 직후 따라잡는다.
 * 레플리카가 여러 개여도 ShedLock 으로 한 파드만, 회원마다 잠근 뒤 다시 확인하므로 겹쳐 돌아도 한 번만 해제된다.
 * 만료 후 이 작업 전까지(최대 1분)는 아직 정지 상태 — 로그인은 막힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuspensionExpiryScheduler {

    private final UserRepository userRepository;
    private final UserStatusService userStatusService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${app.user.suspension-expiry-interval:1m}")
    @SchedulerLock(name = "user-suspension-expiry", lockAtMostFor = "5m")
    public void releaseExpired() {
        List<Long> userIds = userRepository.findSuspensionExpiredIds(UserStatus.SUSPENDED, LocalDateTime.now(clock));
        for (Long userId : userIds) {
            try {
                userStatusService.expireSuspension(userId);
            } catch (RuntimeException e) {
                // 한 회원 실패가 나머지를 막지 않게 — 다음 주기에 다시
                log.error("이용정지 기간 만료 자동 해제 실패 — 다음 주기에 다시 시도 userId={}", userId, e);
            }
        }
    }
}
