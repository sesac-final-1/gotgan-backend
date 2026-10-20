package com.sesac.gojigi.settlement.settlement.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 정산 주차 — ISO 주차(월~일) 연말 · 연초 경계. 2026년은 1월 1일이 목요일이라 53주차가 있다 */
class SettlementWeekTest {

    @ParameterizedTest(name = "{0} → {1} (월 {2})")
    @CsvSource({
            "2025-12-29, 2026-W01, 2025-12-29",   // 새해 1주차가 전년 12월에 시작
            "2026-01-01, 2026-W01, 2025-12-29",
            "2026-01-04, 2026-W01, 2025-12-29",   // 일요일까지 같은 주
            "2026-01-05, 2026-W02, 2026-01-05",
            "2026-10-19, 2026-W43, 2026-10-19",
            "2026-12-28, 2026-W53, 2026-12-28",   // 53주차
            "2027-01-01, 2026-W53, 2026-12-28",   // 새해 첫날이 전년 53주차
            "2027-01-03, 2026-W53, 2026-12-28",
            "2027-01-04, 2027-W01, 2027-01-04",
    })
    @DisplayName("아무 날짜 → 그 주 월요일 · ISO 주차 표기")
    void of_isoWeek(LocalDate day, String iso, LocalDate monday) {
        SettlementWeek week = SettlementWeek.of(day);

        assertThat(week.start()).isEqualTo(monday);
        assertThat(week.isoWeek()).isEqualTo(iso);
    }

    @ParameterizedTest(name = "{0} → 월 {1}")
    @CsvSource({
            "2026-W01, 2025-12-29",
            "2026-W43, 2026-10-19",
            "2026-W53, 2026-12-28",
            "2027-W01, 2027-01-04",
    })
    @DisplayName("ISO 주차 문자열 → 그 주 월요일 (경계 주차 포함)")
    void parse(String iso, LocalDate monday) {
        assertThat(SettlementWeek.parse(iso).start()).isEqualTo(monday);
    }

    @Test
    @DisplayName("2025 ~ 2028 모든 주차가 parse ↔ isoWeek 왕복 일치")
    void roundTrip_everyWeek() {
        for (LocalDate monday = LocalDate.of(2025, 1, 6); monday.isBefore(LocalDate.of(2029, 1, 1)); monday = monday.plusWeeks(1)) {
            SettlementWeek week = new SettlementWeek(monday);
            assertThat(SettlementWeek.parse(week.isoWeek())).isEqualTo(week);
        }
    }

    @Test
    @DisplayName("53주차 — 종료일 · 구매확정 범위 · 지급 예정일(다음 해 목요일)")
    void week53_endAndPayDate() {
        SettlementWeek week = SettlementWeek.parse("2026-W53");

        assertThat(week.end()).isEqualTo(LocalDate.of(2027, 1, 3));
        assertThat(week.from()).isEqualTo(LocalDate.of(2026, 12, 28).atStartOfDay());
        assertThat(week.until()).isEqualTo(LocalDate.of(2027, 1, 4).atStartOfDay());
        assertThat(week.scheduledPayDate()).isEqualTo(LocalDate.of(2027, 1, 7));
        assertThat(week.scheduledPayDate().getDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
    }

    @Test
    @DisplayName("새해 첫 월요일의 지난주 = 전년 마지막 주차")
    void lastWeek_acrossYear() {
        assertThat(SettlementWeek.lastWeek(LocalDate.of(2027, 1, 4)).isoWeek()).isEqualTo("2026-W53");
        assertThat(SettlementWeek.lastWeek(LocalDate.of(2026, 1, 5)).isoWeek()).isEqualTo("2026-W01");
    }

    @Test
    @DisplayName("월요일이 아닌 시작일은 거부")
    void notMonday_rejected() {
        assertThatThrownBy(() -> new SettlementWeek(LocalDate.of(2026, 10, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
