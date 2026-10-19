package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.settlement.entity.SettlementHistory.Actor;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주간 정산 집계 — 매주 월요일 03:00(KST) 지난주 월~일 구매확정분 (가이드라인 10장 · STL-03).
 * 레플리카 2개여도 ShedLock 으로 한 번만, 집계 자체도 스토어 × 주차 UK 로 멱등. 관리자 "지금 집계"는 같은 서비스 메서드를 쓴다.
 * 클러스터가 매일 07:00~21:00 에만 떠 있으므로 03:00 실행은 시연 환경에선 일어나지 않는다 — 시연은 "지금 집계" 버튼으로.
 */
@Component
@RequiredArgsConstructor
public class SettlementScheduler {

    private final SettlementCommandService settlementCommandService;
    private final Clock clock;

    @Scheduled(cron = "${app.scheduler.weekly-aggregation-cron:0 0 3 * * MON}", zone = "Asia/Seoul")
    @SchedulerLock(name = "settlement-weekly-aggregation", lockAtMostFor = "10m", lockAtLeastFor = "1m")
    public void aggregateLastWeek() {
        settlementCommandService.aggregate(SettlementWeek.lastWeek(LocalDate.now(clock)), Actor.SYSTEM);
    }
}
