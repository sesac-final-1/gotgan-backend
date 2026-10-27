package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.ProcessedEvent;
import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.user.dto.event.OrderItemConfirmedPayload;
import com.sesac.gojigi.user.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 누적 구매확정 금액 (OrderItemConfirmed) — users.total_confirmed_amt 에 상품주문 금액을 더한다. 회원등급의 기준값.
 * 등급 재계산은 기준이 정해지면 여기서 함께 (TODO). 구매확정 뒤에는 취소 · 반품이 없어 빼는 경우가 없다.
 * 같은 eventId 재전달은 processed_event 로 건너뛴다 — 누적값 UPDATE 와 같은 트랜잭션.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmedAmountService {

    private final UserRepository userRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;

    /** @return 누적했으면 true, 재전달 · 탈퇴 회원이면 false */
    public boolean accumulate(EventEnvelope<OrderItemConfirmedPayload> event) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> doAccumulate(event)));
        } catch (DataIntegrityViolationException e) {
            // 같은 eventId 가 동시에 두 번 → processed_event uk 에 걸린 쪽은 롤백, 먼저 커밋된 쪽이 누적함
            if (processedEventRepository.existsByEventId(event.eventId())) {
                return false;
            }
            throw e;
        }
    }

    private boolean doAccumulate(EventEnvelope<OrderItemConfirmedPayload> event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return false;
        }
        OrderItemConfirmedPayload p = event.payload();
        boolean updated = userRepository.addConfirmedAmount(p.buyerId(), p.itemAmt()) == 1;
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        if (updated) {
            log.info("구매확정 금액 누적 userId={} orderItemId={} itemAmt={} eventId={}",
                    p.buyerId(), p.orderItemId(), p.itemAmt(), event.eventId());
        } else {
            log.warn("구매확정 금액 누적 — 회원 없음 userId={} orderItemId={} eventId={}", p.buyerId(), p.orderItemId(), event.eventId());
        }
        return updated;
    }
}
