package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.user.dto.event.OrderItemConfirmedPayload;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 누적 구매확정 금액 — 실제 MySQL. 상품주문 금액을 더하고, 같은 eventId 재전달은 한 번만 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, ConfirmedAmountService.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConfirmedAmountServiceIntegrationTest {

    @Autowired private ConfirmedAmountService confirmedAmountService;
    @Autowired private UserRepository userRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;

    private final List<Long> users = new ArrayList<>();
    private final List<String> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        processedEventRepository.findAll().stream()
                .filter(e -> eventIds.contains(e.getEventId()))
                .forEach(processedEventRepository::delete);
        userRepository.deleteAllByIdInBatch(users);
    }

    private EventEnvelope<OrderItemConfirmedPayload> confirmed(Long userId, long orderItemId, long itemAmt) {
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);
        return new EventEnvelope<>(eventId, "OrderItemConfirmed", OffsetDateTime.now(), "x", 1,
                new OrderItemConfirmedPayload(orderItemId, "20261027-000001", userId, itemAmt));
    }

    @Test
    @DisplayName("상품주문 금액을 누적 — 같은 eventId 재전달은 건너뜀, 없는 회원은 누적 없이 처리됨")
    void accumulate_onceAndUnknownUser() {
        Long userId = userRepository.save(User.signUp("cf-" + System.nanoTime() + "@gotgan.com", "확정", null, null)).getId();
        users.add(userId);
        EventEnvelope<OrderItemConfirmedPayload> first = confirmed(userId, 11L, 20_000L);
        EventEnvelope<OrderItemConfirmedPayload> unknown = confirmed(Long.MAX_VALUE, 13L, 5_000L);

        assertThat(confirmedAmountService.accumulate(first)).isTrue();
        assertThat(confirmedAmountService.accumulate(first)).isFalse();
        assertThat(confirmedAmountService.accumulate(confirmed(userId, 12L, 8_000L))).isTrue();
        assertThat(confirmedAmountService.accumulate(unknown)).isFalse();

        assertThat(userRepository.findById(userId).orElseThrow().getTotalConfirmedAmt()).isEqualTo(28_000L);
        assertThat(processedEventRepository.existsByEventId(unknown.eventId())).isTrue();
    }
}
