package com.sesac.gojigi.order.order.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문번호 발급 — yyyyMMdd-{그날 순번 6자리}. 그날 줄(order_no_sequence)을 한 문장으로 올리고 그 값을 받으므로 동시 주문도 겹치지 않는다.
 * 주문 트랜잭션 밖에서 부른다 — 안에서 부르면 주문이 커밋될 때까지 그날 줄이 잠겨 주문이 줄을 선다.
 * 주문이 실패하면 받은 번호는 쓰이지 않고 비어 있게 된다.
 */
@Service
@RequiredArgsConstructor
public class OrderNoIssuer {

    private static final DateTimeFormatter ORDER_DATE = DateTimeFormatter.BASIC_ISO_DATE;   // 20261022
    private static final String INCREMENT = """
            INSERT INTO order_no_sequence (order_date, last_no) VALUES (?, LAST_INSERT_ID(1))
            ON DUPLICATE KEY UPDATE last_no = LAST_INSERT_ID(last_no + 1)""";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** LAST_INSERT_ID 는 연결별 값이라 올리기 · 읽기를 한 트랜잭션(같은 연결)에서 */
    @Transactional
    public String next() {
        LocalDate today = LocalDate.now(clock);
        jdbc.update(INCREMENT, today);
        Long no = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return today.format(ORDER_DATE) + "-" + String.format("%06d", no);
    }
}
