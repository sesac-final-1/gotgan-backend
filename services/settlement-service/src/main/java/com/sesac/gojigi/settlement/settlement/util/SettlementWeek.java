package com.sesac.gojigi.settlement.settlement.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * 정산 주차 — 월요일 ~ 일요일(KST), API 표기는 ISO 주차 "2026-W43" (API 명세 /api/admin/settlements?week=).
 * 지급 예정일 = 주차 종료(일) + 4일 = 다음 주 목요일 (목업 정산 화면 기준).
 */
public record SettlementWeek(LocalDate start) {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int PAY_DAYS_AFTER_END = 4;

    public SettlementWeek {
        if (!DayOfWeek.MONDAY.equals(start.getDayOfWeek())) {
            throw new IllegalArgumentException("정산 주차는 월요일 시작: " + start);
        }
    }

    public static SettlementWeek of(LocalDate anyDay) {
        return new SettlementWeek(anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
    }

    /** "2026-W43" → 그 주 월요일 */
    public static SettlementWeek parse(String isoWeek) {
        String[] parts = isoWeek.split("-W");
        LocalDate monday = LocalDate.of(Integer.parseInt(parts[0]), 6, 1)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, Integer.parseInt(parts[1]))
                .with(DayOfWeek.MONDAY);
        return new SettlementWeek(monday);
    }

    /** 지금(KST) 기준 지난주 — 매주 월요일 집계 대상 */
    public static SettlementWeek lastWeek(LocalDate today) {
        return of(today).minusWeeks(1);
    }

    public SettlementWeek minusWeeks(long weeks) {
        return new SettlementWeek(start.minusWeeks(weeks));
    }

    public LocalDate end() {
        return start.plusDays(6);
    }

    /** 이 주차 구매확정 범위 [월 00:00, 다음 월 00:00) */
    public LocalDateTime from() {
        return start.atStartOfDay();
    }

    public LocalDateTime until() {
        return start.plusWeeks(1).atStartOfDay();
    }

    public LocalDate scheduledPayDate() {
        return end().plusDays(PAY_DAYS_AFTER_END);
    }

    public String isoWeek() {
        // 로캘 무관한 ISO 주차 (DateTimeFormatter 의 YYYY · ww 는 로캘마다 주 시작 요일이 달라 쓰지 않음)
        return String.format("%d-W%02d", start.get(IsoFields.WEEK_BASED_YEAR), start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }
}
