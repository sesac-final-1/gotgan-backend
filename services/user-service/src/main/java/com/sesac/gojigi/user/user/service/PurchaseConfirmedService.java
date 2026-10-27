package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.ProcessedEvent;
import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.point.service.PointService;
import com.sesac.gojigi.user.user.dto.event.OrderItemConfirmedPayload;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매확정 (OrderItemConfirmed) — 누적 구매확정 금액(users.total_confirmed_amt, 회원등급 기준)과 포인트 적립을 한 트랜잭션에서.
 * 구매확정 뒤에는 취소 · 반품이 없어(order 상태 전이) 금액을 빼거나 적립을 회수할 일이 없다.
 * - 같은 eventId 재전달은 processed_event 로 건너뜀 (누적 · 적립 · processed_event 가 함께 커밋)
 * - 탈퇴 회원은 누적 · 적립 없이 처리됨으로 남김
 * - 등급 재계산은 기준이 정해지면 여기서 함께 (TODO)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseConfirmedService {

    private final UserRepository userRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PointService pointService;
    private final TransactionTemplate transactionTemplate;

    /** 처리 결과 — earned 는 이번에 적립한 포인트. applied=false 면 재전달 · 탈퇴 회원이라 아무것도 바꾸지 않음 */
    public record Result(boolean applied, long earned) {
    }

    public Result apply(EventEnvelope<OrderItemConfirmedPayload> event) {
        try {
            return transactionTemplate.execute(status -> doApply(event));
        } catch (DataIntegrityViolationException e) {
            // 같은 eventId 가 동시에 두 번 → processed_event · 적립 이력 uk 에 걸린 쪽은 롤백, 먼저 커밋된 쪽이 반영함
            if (processedEventRepository.existsByEventId(event.eventId())) {
                return new Result(false, 0);
            }
            throw e;
        }
    }

    private Result doApply(EventEnvelope<OrderItemConfirmedPayload> event) {
        OrderItemConfirmedPayload p = event.payload();
        // 잔액을 바꾸므로 회원 행 잠금 — 포인트 사용 · 회수와 같은 잠금, 트랜잭션의 첫 조회로 (PointService.doReverse 참고)
        Optional<User> locked = userRepository.findByIdForUpdate(p.buyerId());
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return new Result(false, 0);
        }
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        if (locked.isEmpty()) {
            log.warn("구매확정 반영 — 탈퇴 회원이라 처리하지 않음 userId={} orderItemId={} eventId={}",
                    p.buyerId(), p.orderItemId(), event.eventId());
            return new Result(false, 0);
        }
        long earned = pointService.earnConfirmed(locked.get(), p.orderItemId(), p.orderNo(), p.earnedPoint());
        userRepository.addConfirmedAmount(p.buyerId(), p.itemAmt());
        log.info("구매확정 반영 userId={} orderItemId={} itemAmt={} earned={} eventId={}",
                p.buyerId(), p.orderItemId(), p.itemAmt(), earned, event.eventId());
        return new Result(true, earned);
    }
}
