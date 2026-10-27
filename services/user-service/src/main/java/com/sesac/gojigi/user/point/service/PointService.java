package com.sesac.gojigi.user.point.service;

import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.ProcessedEvent;
import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.point.dto.event.OrderPaidPayload;
import com.sesac.gojigi.user.point.dto.request.ReversePointRequest;
import com.sesac.gojigi.user.point.dto.request.UsePointRequest;
import com.sesac.gojigi.user.point.dto.response.MyPointsResponse;
import com.sesac.gojigi.user.point.dto.response.PointRestoreResponse;
import com.sesac.gojigi.user.point.dto.response.PointReversalResponse;
import com.sesac.gojigi.user.point.dto.response.PointUseResponse;
import com.sesac.gojigi.user.point.dto.response.UserPointsResponse;
import com.sesac.gojigi.user.point.entity.PointEarnBlock;
import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.entity.PointUseSaga;
import com.sesac.gojigi.user.point.exception.PointErrorCode;
import com.sesac.gojigi.user.point.exception.PointException;
import com.sesac.gojigi.user.point.repository.PointEarnBlockRepository;
import com.sesac.gojigi.user.point.repository.PointHistoryRepository;
import com.sesac.gojigi.user.point.repository.PointUseSagaRepository;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포인트 — 내 포인트 조회 · 관리자 회원 포인트 조회 · 주문 사용(Saga) · 사용 취소(Saga 보상) · 취소 · 반품 환원 · 회수 · 결제 적립(OrderPaid).
 * 잔액은 users.point_balance 조건부 UPDATE 로만 바꾸고(음수 · 동시 차감 방지), 같은 트랜잭션에서 이력을 남긴다.
 * 멱등: ref_key = sagaId — 같은 sagaId 재요청은 처음 결과를 그대로 돌려준다. 동시에 두 번 오면
 * uk_point_history_type_ref_key 에 걸린 쪽이 롤백되고 먼저 커밋된 결과를 돌려준다.
 * 늦게 도착한 사용 차단: order 가 사용 응답을 못 받고(타임아웃) 보상으로 취소를 먼저 보내면, 사용 기록이 없으니 취소는 0원으로 끝난다.
 * 그때 point_use_saga 에 RELEASED 를 남겨, 뒤늦게 도착한 같은 sagaId 의 사용을 409 POINT_USE_RELEASED 로 거절한다(포인트가 사라지지 않게).
 * 사용도 같은 트랜잭션에서 USED 를 넣어, 사용 · 취소가 겹치면 uk 충돌로 한쪽만 표식을 갖는다 — 어느 순서든 잔액은 원래대로.
 * 늦게 도착한 적립 차단: 취소 · 반품이 적립보다 먼저 오면 회수할 적립이 없어 이력이 남지 않는다. 그래서 환원 · 회수 때 point_earn_block 에
 * 상품주문을 남기고, 적립은 그 상품주문을 건너뛴다. 둘 다 회원 행을 잠그고 처리해 어느 순서든 적립은 0(먼저 취소) 또는 회수(먼저 적립)로 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    private final UserRepository userRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final PointUseSagaRepository pointUseSagaRepository;
    private final PointEarnBlockRepository pointEarnBlockRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * 적립 결과 — earned 는 이번에 적립한 합계, blocked 는 취소 · 반품돼 건너뛴 상품주문 수.
     * duplicate 면 같은 eventId 재전달이라 아무것도 하지 않음
     */
    public record EarnResult(long earned, int blocked, boolean duplicate) {
    }

    /**
     * 결제 적립 (OrderPaid) — 상품주문별 earnedPoint 를 적립한다. 한 트랜잭션: 회원 행 잠금 → 상품주문별 적립 → processed_event.
     * - 같은 eventId 재전달은 processed_event 로 건너뜀. 상품주문 단위로도 ref_key earn-{orderItemId} 라 다른 eventId 로 다시 와도 한 번만
     * - 취소 · 반품된 상품주문(point_earn_block)은 건너뜀 — 취소가 적립보다 먼저 도착한 경우
     * - 탈퇴 회원은 적립하지 않고 처리됨으로 남김. earnedPoint 0 인 상품주문(전액 포인트 결제 등)은 이력 없음
     */
    public EarnResult earn(EventEnvelope<OrderPaidPayload> event) {
        try {
            return transactionTemplate.execute(status -> doEarn(event));
        } catch (DataIntegrityViolationException e) {
            // 같은 eventId 가 동시에 두 번 → processed_event · 이력 uk 에 걸린 쪽은 롤백, 먼저 커밋된 쪽이 적립함
            if (processedEventRepository.existsByEventId(event.eventId())) {
                return new EarnResult(0, 0, true);
            }
            throw e;
        }
    }

    private EarnResult doEarn(EventEnvelope<OrderPaidPayload> event) {
        OrderPaidPayload p = event.payload();
        // 잠금이 트랜잭션의 첫 조회여야 한다 — 먼저 일반 조회를 하면 그때 스냅샷(REPEATABLE READ)이 잡혀,
        // 잠금을 기다리는 동안 커밋된 취소 표식(point_earn_block)을 못 보고 적립해 버린다
        Optional<User> locked = userRepository.findByIdForUpdate(p.buyerId());   // 회수(reverse)와 순서를 맞추는 잠금
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return new EarnResult(0, 0, true);
        }
        if (locked.isEmpty()) {
            log.warn("포인트 적립 — 탈퇴 회원이라 처리하지 않음 userId={} orderNo={} eventId={}", p.buyerId(), p.orderNo(), event.eventId());
            processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
            return new EarnResult(0, 0, false);
        }
        long balance = locked.get().getPointBalance();
        long earned = 0;
        int blocked = 0;
        for (OrderPaidPayload.Item item : p.items()) {
            String refKey = PointHistory.earnRefKey(item.orderItemId());
            if (item.earnedPoint() <= 0 || pointHistoryRepository.existsByTypeAndRefKey(PointType.EARN, refKey)) {
                continue;
            }
            if (pointEarnBlockRepository.existsByOrderItemId(item.orderItemId())) {
                log.info("포인트 적립 건너뜀 — 이미 취소 · 반품된 상품주문 userId={} orderNo={} orderItemId={} earnedPoint={}",
                        p.buyerId(), p.orderNo(), item.orderItemId(), item.earnedPoint());
                blocked++;
                continue;
            }
            userRepository.addPoint(p.buyerId(), item.earnedPoint());
            balance += item.earnedPoint();
            earned += item.earnedPoint();
            pointHistoryRepository.save(PointHistory.earn(locked.get(), item.orderItemId(), p.orderNo(),
                    item.earnedPoint(), balance, refKey));
        }
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        log.info("포인트 적립 userId={} orderNo={} earned={} blocked={} balanceAfter={} eventId={}",
                p.buyerId(), p.orderNo(), earned, blocked, balance, event.eventId());
        return new EarnResult(earned, blocked, false);
    }

    /** 사용 결과 — created=false 면 같은 sagaId 재요청 (컨트롤러가 201 / 200 을 나눈다) */
    public record UseResult(PointUseResponse response, boolean created) {
    }

    /** 내 포인트 — 잔액과 이력을 한 트랜잭션에서 읽어 서로 맞게 */
    public MyPointsResponse getMyPoints(Long userId) {
        return transactionTemplate.execute(status -> {
            User user = findActive(userId);
            return MyPointsResponse.of(user.getPointBalance(), pointHistoryRepository.findAllByUserIdLatestFirst(userId));
        });
    }

    /** 관리자 회원 포인트 — 잔액 + 이력 한 페이지(최신순)를 한 트랜잭션에서 읽는다. 탈퇴 회원은 USER_NOT_FOUND */
    public UserPointsResponse getUserPoints(Long userId, int page, int size) {
        return transactionTemplate.execute(status -> {
            User user = findActive(userId);
            return UserPointsResponse.of(user.getPointBalance(),
                    pointHistoryRepository.findByUserId(userId, PageRequest.of(page, size)));
        });
    }

    /** 주문 결제에 포인트 사용 — 잔액이 모자라면 POINT_INSUFFICIENT(409), 이미 취소된 sagaId 면 POINT_USE_RELEASED(409) */
    public UseResult use(UsePointRequest request) {
        try {
            return transactionTemplate.execute(status -> doUse(request));
        } catch (DataIntegrityViolationException e) {
            // 표식 · 이력 uk 충돌 — 이쪽 차감은 롤백됨
            // ① 같은 sagaId 의 취소가 먼저 커밋됨 → 거절 ② 같은 sagaId 사용이 동시에 두 번 → 먼저 커밋된 결과
            if (isReleased(request.sagaId())) {
                throw releasedUse(request);
            }
            return pointHistoryRepository.findByTypeAndRefKey(PointType.USE, request.sagaId())
                    .map(existing -> new UseResult(sameRequestOrThrow(existing, request), false))
                    .orElseThrow(() -> e);
        }
    }

    /** 사용 취소 (Saga 보상) — 그 sagaId 로 쓴 포인트를 돌려준다. 사용 기록이 없으면(사용 단계 실패 · 미호출 · 아직 도착 전) 해제 표식만 남긴다. */
    public PointRestoreResponse cancelUse(String sagaId) {
        try {
            return transactionTemplate.execute(status -> doCancelUse(sagaId));
        } catch (DataIntegrityViolationException e) {
            // ① 같은 sagaId 취소가 동시에 두 번 → 먼저 커밋된 RESTORE
            // ② 해제 표식을 넣으려는데 그 사이 사용이 커밋됨(표식 uk 충돌) → 다시 처리하면 그 사용을 찾아 돌려준다
            Optional<PointHistory> restored = pointHistoryRepository.findByTypeAndRefKey(PointType.RESTORE, sagaId);
            if (restored.isPresent()) {
                return PointRestoreResponse.from(restored.get());
            }
            return transactionTemplate.execute(status -> doCancelUse(sagaId));
        }
    }

    /**
     * 취소 · 반품 환원 · 회수 (order 취소 · 반품 Saga) — 쓴 포인트를 돌려주고, 이 상품주문으로 실제 적립된 포인트만큼 회수한다.
     * 한 트랜잭션: 환원 먼저(돌려준 포인트로 회수할 수 있게) → 회수는 잔액 0 까지만, 못 뺀 차액은 shortfall 로 남긴다.
     * 멱등: ref_key = claim-{claimId}-{orderItemId} — 이미 처리했으면 처음 결과 그대로. 탈퇴 회원이면 아무것도 하지 않고 0.
     */
    public PointReversalResponse reverse(ReversePointRequest request) {
        try {
            return transactionTemplate.execute(status -> doReverse(request));
        } catch (DataIntegrityViolationException e) {
            // 같은 클레임이 동시에 두 번 → uk 에 걸린 쪽은 롤백, 먼저 커밋된 결과
            return processedReversal(request).orElseThrow(() -> e);
        }
    }

    private PointReversalResponse doReverse(ReversePointRequest request) {
        // 잠금이 트랜잭션의 첫 조회여야 한다 — 먼저 일반 조회를 하면 그때 스냅샷(REPEATABLE READ)이 잡혀,
        // 잠금을 기다리는 동안 커밋된 적립(EARN)을 못 보고 회수를 0 으로 끝낸다
        Optional<User> locked = userRepository.findByIdForUpdate(request.userId());   // 회수는 현재 잔액으로 계산 — 행 잠금
        Optional<PointReversalResponse> processed = processedReversal(request);
        if (processed.isPresent()) {
            return processed.get();
        }
        if (locked.isEmpty()) {
            log.warn("포인트 환원 · 회수 — 탈퇴 회원이라 처리하지 않음 userId={} claimId={}", request.userId(), request.claimId());
            return PointReversalResponse.of(request.claimId(), request.orderItemId(), null, null);
        }
        if (!pointEarnBlockRepository.existsByOrderItemId(request.orderItemId())) {
            pointEarnBlockRepository.save(new PointEarnBlock(request.orderItemId()));   // 이후 도착하는 적립은 건너뜀
        }
        long balance = locked.get().getPointBalance();
        PointHistory restore = null;
        if (request.restoreAmount() > 0) {
            userRepository.addPoint(request.userId(), request.restoreAmount());
            balance += request.restoreAmount();
            restore = pointHistoryRepository.save(PointHistory.restore(locked.get(), request.orderItemId(),
                    request.orderNo(), request.restoreAmount(), balance, request.refKey()));
        }
        PointHistory reclaim = null;
        long reclaimable = Math.min(request.reclaimAmount(), earnedFor(request.orderItemId()));
        long taken = Math.min(reclaimable, balance);
        if (taken > 0) {
            userRepository.deductPoint(request.userId(), taken);   // 잠금 + taken ≤ 잔액이라 항상 1건
            balance -= taken;
            reclaim = pointHistoryRepository.save(PointHistory.reclaim(locked.get(), request.orderItemId(),
                    request.orderNo(), taken, reclaimable - taken, balance, request.refKey()));
        } else if (reclaimable > 0) {
            log.warn("포인트 회수 — 잔액 0 이라 회수하지 못함 userId={} claimId={} shortfall={}",
                    request.userId(), request.claimId(), reclaimable);
        }
        log.info("포인트 환원 · 회수 userId={} orderNo={} claimId={} restored={} reclaimed={} balanceAfter={}",
                request.userId(), request.orderNo(), request.claimId(), request.restoreAmount(), taken, balance);
        return PointReversalResponse.of(request.claimId(), request.orderItemId(), restore, reclaim);
    }

    /** 이미 처리한 클레임이면 그 결과 — RESTORE · RECLAIM 중 하나라도 있으면 처리된 것 (한 트랜잭션에서 같이 남기므로) */
    private Optional<PointReversalResponse> processedReversal(ReversePointRequest request) {
        Optional<PointHistory> restore = pointHistoryRepository.findByTypeAndRefKey(PointType.RESTORE, request.refKey());
        Optional<PointHistory> reclaim = pointHistoryRepository.findByTypeAndRefKey(PointType.RECLAIM, request.refKey());
        if (restore.isEmpty() && reclaim.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(PointReversalResponse.of(request.claimId(), request.orderItemId(), restore.orElse(null),
                reclaim.orElse(null)));
    }

    /** 이 상품주문으로 실제 적립된 포인트 — 적립 전이면 0 (회수할 게 없음) */
    private long earnedFor(Long orderItemId) {
        return pointHistoryRepository.findByOrderItemIdAndTypeIn(orderItemId, List.of(PointType.EARN)).stream()
                .mapToLong(PointHistory::getAmount).sum();
    }

    private UseResult doUse(UsePointRequest request) {
        Optional<PointHistory> existing = pointHistoryRepository.findByTypeAndRefKey(PointType.USE, request.sagaId());
        if (existing.isPresent()) {
            return new UseResult(sameRequestOrThrow(existing.get(), request), false);
        }
        if (isReleased(request.sagaId())) {
            throw releasedUse(request);
        }
        // 사용 표식 — 같은 sagaId 의 취소가 지금 해제 표식을 넣는 중이면 여기서 uk 충돌(커밋 순서대로 한쪽만)
        pointUseSagaRepository.saveAndFlush(PointUseSaga.used(request.sagaId()));
        if (userRepository.deductPoint(request.userId(), request.amount()) == 0) {
            findActive(request.userId());   // 없는 회원 · 탈퇴면 USER_NOT_FOUND, 있으면 잔액 부족
            throw new PointException(PointErrorCode.POINT_INSUFFICIENT);
        }
        User user = findActive(request.userId());   // 조건부 UPDATE 가 영속성 컨텍스트를 비워 다시 읽는다 (차감 후 잔액)
        PointHistory use = pointHistoryRepository.save(
                PointHistory.use(user, request.orderNo(), request.amount(), user.getPointBalance(), request.sagaId()));
        log.info("포인트 사용 userId={} orderNo={} amount={} balanceAfter={} sagaId={}",
                user.getId(), request.orderNo(), request.amount(), user.getPointBalance(), request.sagaId());
        return new UseResult(PointUseResponse.from(use), true);
    }

    private PointRestoreResponse doCancelUse(String sagaId) {
        Optional<PointHistory> restored = pointHistoryRepository.findByTypeAndRefKey(PointType.RESTORE, sagaId);
        if (restored.isPresent()) {
            return PointRestoreResponse.from(restored.get());
        }
        Optional<PointHistory> used = pointHistoryRepository.findByTypeAndRefKey(PointType.USE, sagaId);
        if (used.isEmpty()) {
            if (!pointUseSagaRepository.existsBySagaId(sagaId)) {
                // 해제 표식 — 사용이 아직 오지 않았을 수 있다(order 타임아웃). 늦게 오면 409 로 막는다
                pointUseSagaRepository.saveAndFlush(PointUseSaga.released(sagaId));
            }
            log.info("포인트 사용 취소 — 사용 기록 없음, 해제 표식만 sagaId={}", sagaId);
            return PointRestoreResponse.nothingToRestore(sagaId);
        }
        PointHistory use = used.get();
        Long userId = use.getUser().getId();
        if (userRepository.addPoint(userId, use.getAmount()) == 0) {
            log.warn("포인트 사용 취소 — 탈퇴 회원이라 돌려주지 않음 userId={} amount={} sagaId={}", userId, use.getAmount(), sagaId);
            return PointRestoreResponse.nothingToRestore(sagaId);
        }
        User user = findActive(userId);
        PointHistory restore = pointHistoryRepository.save(
                PointHistory.restore(user, null, use.getOrderNo(), use.getAmount(), user.getPointBalance(), sagaId));
        log.info("포인트 사용 취소 userId={} orderNo={} amount={} balanceAfter={} sagaId={}",
                userId, use.getOrderNo(), use.getAmount(), user.getPointBalance(), sagaId);
        return PointRestoreResponse.from(restore);
    }

    private boolean isReleased(String sagaId) {
        return pointUseSagaRepository.findBySagaId(sagaId).map(PointUseSaga::isReleased).orElse(false);
    }

    private PointException releasedUse(UsePointRequest request) {
        log.warn("포인트 사용 거절 — 이미 취소(보상)된 sagaId 의 늦은 요청, 차감 안 함 userId={} orderNo={} amount={} sagaId={}",
                request.userId(), request.orderNo(), request.amount(), request.sagaId());
        return new PointException(PointErrorCode.POINT_USE_RELEASED);
    }

    /** 같은 sagaId 인데 회원 · 금액이 다르면 order 쪽 멱등 키 재사용 버그 */
    private static PointUseResponse sameRequestOrThrow(PointHistory existing, UsePointRequest request) {
        if (!existing.getUser().getId().equals(request.userId()) || existing.getAmount() != request.amount()) {
            throw new PointException(PointErrorCode.POINT_USE_CONFLICT);
        }
        return PointUseResponse.from(existing);
    }

    private User findActive(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
    }
}
