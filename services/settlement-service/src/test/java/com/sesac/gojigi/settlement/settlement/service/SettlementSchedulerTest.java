package com.sesac.gojigi.settlement.settlement.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sesac.gojigi.settlement.settlement.entity.SettlementHistory.Actor;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 기동 시 따라잡기 — 클러스터가 07:00~21:00 에만 떠 있어 월요일 03:00 cron 이 돌지 않는 문제 보완 */
class SettlementSchedulerTest {

    /** 2026-10-28(수) 08:00 KST — 클러스터가 막 뜬 시각, 지난주 = 10/19(월)~10/25(일) */
    private static final Clock WEDNESDAY_MORNING = Clock.fixed(Instant.parse("2026-10-27T23:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final SettlementWeek LAST_WEEK = SettlementWeek.of(LocalDate.of(2026, 10, 19));

    private final SettlementCommandService commandService = mock(SettlementCommandService.class);

    @Test
    @DisplayName("기동하면 지난주를 시스템으로 집계한다 — 월요일이 아니어도 (멱등이라 이미 집계된 주여도 안전)")
    void startup_aggregatesLastWeek() {
        when(commandService.aggregate(any(), any())).thenReturn(new SettlementCommandService.AggregateResult(LAST_WEEK.isoWeek(), 1, 0, 0, 0));

        new SettlementScheduler(commandService, WEDNESDAY_MORNING, true).catchUpOnStartup();

        verify(commandService).aggregate(eq(LAST_WEEK), eq(Actor.SYSTEM));
    }

    @Test
    @DisplayName("꺼 두면(catch-up-on-startup=false) 집계하지 않는다")
    void startup_disabled() {
        new SettlementScheduler(commandService, WEDNESDAY_MORNING, false).catchUpOnStartup();

        verify(commandService, never()).aggregate(any(), any());
    }

    @Test
    @DisplayName("집계가 실패해도(DB 장애 등) 기동을 막지 않는다 — 다음 기동 · 정시 · 관리자 \"지금 집계\"로 다시")
    void startup_failureDoesNotBlockBoot() {
        when(commandService.aggregate(any(), any())).thenThrow(new DataAccessResourceFailureException("DB 연결 끊김"));

        assertThatCode(() -> new SettlementScheduler(commandService, WEDNESDAY_MORNING, true).catchUpOnStartup())
                .doesNotThrowAnyException();
    }
}
