package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.settlement.entity.SettlementHistory.Actor;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.time.Clock;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주간 정산 집계 — 매주 월요일 03:00(KST) 지난주 월~일 구매확정분 (가이드라인 10장 · STL-03).
 * 레플리카 2개여도 ShedLock 으로 한 번만, 집계 자체도 스토어 × 주차 UK 로 멱등. 관리자 "지금 집계"는 같은 서비스 메서드를 쓴다.
 *
 * 기동 시 따라잡기: 클러스터가 매일 07:00~21:00 에만 떠 있어 월요일 03:00 cron 은 시연 환경에서 한 번도 돌지 않는다.
 * 실제 환경에서도 그 시각에 파드 · DB 가 죽어 있으면 한 주가 빠진다. 그래서 앱이 뜰 때마다 지난주를 한 번 집계한다 —
 * 멱등이라 이미 집계된 주를 다시 돌려도 늦게 들어온 구매확정만 붙고 결과는 같다. 실패해도 기동은 막지 않는다(다음 기동 · 정시에 다시).
 */
@Slf4j
@Component
public class SettlementScheduler {

    private final SettlementCommandService settlementCommandService;
    private final Clock clock;
    private final boolean catchUpOnStartup;

    public SettlementScheduler(SettlementCommandService settlementCommandService, Clock clock,
            @Value("${app.scheduler.catch-up-on-startup:true}") boolean catchUpOnStartup) {
        this.settlementCommandService = settlementCommandService;
        this.clock = clock;
        this.catchUpOnStartup = catchUpOnStartup;
    }

    @Scheduled(cron = "${app.scheduler.weekly-aggregation-cron:0 0 3 * * MON}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-weekly-aggregation", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void aggregateLastWeek() {
        settlementCommandService.aggregate(SettlementWeek.lastWeek(LocalDate.now(clock)), Actor.SYSTEM);
    }

    /** 기동 직후 지난주 집계 — 정시 집계와 같은 잠금 이름이라 레플리카 여러 개가 동시에 떠도 한 파드만 */
    @EventListener(ApplicationReadyEvent.class)
    @SchedulerLock(name = "settlement-weekly-aggregation", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void catchUpOnStartup() {
        if (!catchUpOnStartup) {
            return;
        }
        SettlementWeek lastWeek = SettlementWeek.lastWeek(LocalDate.now(clock));
        try {
            SettlementCommandService.AggregateResult result = settlementCommandService.aggregate(lastWeek, Actor.SYSTEM);
            log.info("기동 시 주간 집계 따라잡기 week={} created={} updated={}", result.week(), result.created(), result.updated());
        } catch (RuntimeException e) {
            log.error("기동 시 주간 집계 실패 — 다음 기동 · 정시 집계 · 관리자 \"지금 집계\"로 다시 week={} cause={}",
                    lastWeek.isoWeek(), e.toString());
        }
    }
}
