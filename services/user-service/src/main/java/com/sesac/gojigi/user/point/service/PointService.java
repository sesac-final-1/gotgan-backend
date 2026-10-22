package com.sesac.gojigi.user.point.service;

import com.sesac.gojigi.user.point.dto.request.UsePointRequest;
import com.sesac.gojigi.user.point.dto.response.MyPointsResponse;
import com.sesac.gojigi.user.point.dto.response.PointRestoreResponse;
import com.sesac.gojigi.user.point.dto.response.PointUseResponse;
import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.exception.PointErrorCode;
import com.sesac.gojigi.user.point.exception.PointException;
import com.sesac.gojigi.user.point.repository.PointHistoryRepository;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포인트 — 내 포인트 조회 · 주문 사용(Saga) · 사용 취소(Saga 보상). 적립(OrderPaid) · 클레임 환원 · 회수는 후속.
 * 잔액은 users.point_balance 조건부 UPDATE 로만 바꾸고(음수 · 동시 차감 방지), 같은 트랜잭션에서 이력을 남긴다.
 * 멱등: ref_key = sagaId — 같은 sagaId 재요청은 처음 결과를 그대로 돌려준다. 동시에 두 번 오면
 * uk_point_history_type_ref_key 에 걸린 쪽이 롤백되고 먼저 커밋된 결과를 돌려준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointService {

    private final UserRepository userRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final TransactionTemplate transactionTemplate;

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

    /** 주문 결제에 포인트 사용 — 잔액이 모자라면 POINT_INSUFFICIENT(409) */
    public UseResult use(UsePointRequest request) {
        try {
            return transactionTemplate.execute(status -> doUse(request));
        } catch (DataIntegrityViolationException e) {
            // 같은 sagaId 가 동시에 두 번 — 이쪽 차감은 롤백됨, 먼저 커밋된 결과를 돌려준다
            return pointHistoryRepository.findByTypeAndRefKey(PointType.USE, request.sagaId())
                    .map(existing -> new UseResult(sameRequestOrThrow(existing, request), false))
                    .orElseThrow(() -> e);
        }
    }

    /** 사용 취소 (Saga 보상) — 그 sagaId 로 쓴 포인트를 돌려준다. 사용 기록이 없으면(사용 단계 실패 · 미호출) 할 일 없음. */
    public PointRestoreResponse cancelUse(String sagaId) {
        try {
            return transactionTemplate.execute(status -> doCancelUse(sagaId));
        } catch (DataIntegrityViolationException e) {
            return pointHistoryRepository.findByTypeAndRefKey(PointType.RESTORE, sagaId)
                    .map(PointRestoreResponse::from)
                    .orElseThrow(() -> e);
        }
    }

    private UseResult doUse(UsePointRequest request) {
        Optional<PointHistory> existing = pointHistoryRepository.findByTypeAndRefKey(PointType.USE, request.sagaId());
        if (existing.isPresent()) {
            return new UseResult(sameRequestOrThrow(existing.get(), request), false);
        }
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
            log.info("포인트 사용 취소 — 사용 기록 없음, 할 일 없음 sagaId={}", sagaId);
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
