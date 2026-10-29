package com.sesac.gojigi.store.apply.scheduler;

import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.apply.service.ApplicationReviewService;
import com.sesac.gojigi.store.apply.service.SupplementDeadline;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보완 기한이 지난 신청 자동 반려 (STR-09, store.md 7-1절) — 보완요청 + 기한(운영 7일)이 지난 SUPPLEMENT 신청을 SYSTEM 반려.
 * 건마다 따로 트랜잭션: 그 사이 재제출과 겹쳐 @Version 충돌이면 그 건만 건너뛰고, 다음 주기엔 PENDING 이라 대상이 아니다.
 * 재제출은 기한 정각부터 막히므로(2-3절) 주기만큼 늦어도 상태 · 메일 시각만 늦다.
 */
@Slf4j
@Component
public class SupplementExpiryScheduler {

    private final StoreApplicationRepository storeApplicationRepository;
    private final ApplicationReviewService applicationReviewService;
    private final SupplementDeadline supplementDeadline;
    private final Clock clock;
    private final int batchSize;

    public SupplementExpiryScheduler(StoreApplicationRepository storeApplicationRepository,
            ApplicationReviewService applicationReviewService, SupplementDeadline supplementDeadline, Clock clock,
            @Value("${app.scheduler.supplement-expiry.batch-size:100}") int batchSize) {
        this.storeApplicationRepository = storeApplicationRepository;
        this.applicationReviewService = applicationReviewService;
        this.supplementDeadline = supplementDeadline;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.supplement-expiry.interval:10m}",
            initialDelayString = "${app.scheduler.supplement-expiry.initial-delay:60s}")
    @SchedulerLock(name = "store-supplement-expiry", lockAtMostFor = "5m")   // 레플리카가 2개여도 한 파드만
    public void rejectExpired() {
        LocalDateTime before = supplementDeadline.requestedBeforeExpired(LocalDateTime.now(clock));
        List<Long> ids = storeApplicationRepository.findExpiredSupplementIds(before, Limit.of(batchSize));
        for (Long applicationId : ids) {
            try {
                applicationReviewService.rejectExpiredSupplement(applicationId);
            } catch (RuntimeException e) {
                // 그 사이 재제출 · 관리자 처리가 먼저 저장됨(@Version) — 다음 주기에는 대상이 아니다
                log.warn("자동 반려 건너뜀 applicationId={} cause={}", applicationId, e.toString());
            }
        }
    }
}
