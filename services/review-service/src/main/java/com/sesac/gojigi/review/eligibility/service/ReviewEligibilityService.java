package com.sesac.gojigi.review.eligibility.service;

import com.sesac.gojigi.review.common.event.EventEnvelope;
import com.sesac.gojigi.review.common.event.ProcessedEvent;
import com.sesac.gojigi.review.common.event.ProcessedEventRepository;
import com.sesac.gojigi.review.eligibility.dto.event.OrderItemConfirmedPayload;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 리뷰 작성 자격 저장 (REV-01, review.md 11절) — OrderItemConfirmed 1건 = 상품주문 1개 자격. 담당 이경석.
 * processed_event 와 자격을 한 트랜잭션에서 저장 — 같은 eventId 재전달은 건너뛰고, 다른 eventId 로 다시 발행돼도
 * 상품주문당 자격 1개(uk_review_eligibility_order_item). 구매확정 뒤에는 취소 · 반품이 없어 자격을 지울 일이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewEligibilityService {

    /** 10/28 이전 발행 이벤트에 구매자 표시 이름이 없을 때 — buyer_display_name NOT NULL */
    static final String UNKNOWN_BUYER_NAME = "구매자";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ReviewEligibilityRepository eligibilityRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;

    /** @return 이번에 자격을 새로 저장했으면 true (재전달 · 이미 있는 상품주문이면 false) */
    public boolean grant(EventEnvelope<OrderItemConfirmedPayload> event) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> doGrant(event)));
        } catch (DataIntegrityViolationException e) {
            // 같은 이벤트 · 상품주문이 동시에 두 번 → uk 에 걸린 쪽은 롤백, 먼저 커밋된 쪽이 저장함
            if (processedEventRepository.existsByEventId(event.eventId())
                    || eligibilityRepository.findByOrderItemId(event.payload().orderItemId()).isPresent()) {
                return false;
            }
            throw e;
        }
    }

    private boolean doGrant(EventEnvelope<OrderItemConfirmedPayload> event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return false;
        }
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        OrderItemConfirmedPayload p = event.payload();
        if (eligibilityRepository.findByOrderItemId(p.orderItemId()).isPresent()) {
            log.info("리뷰 작성 자격 — 이미 있는 상품주문이라 건너뜀 orderItemId={} eventId={}", p.orderItemId(), event.eventId());
            return false;
        }
        String buyerName = p.buyerDisplayName();
        if (buyerName == null || buyerName.isBlank()) {
            log.warn("리뷰 작성 자격 — 구매자 표시 이름 없음, '{}'로 저장 orderItemId={} eventId={}",
                    UNKNOWN_BUYER_NAME, p.orderItemId(), event.eventId());
            buyerName = UNKNOWN_BUYER_NAME;
        }
        ReviewEligibility eligibility = eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(p.orderItemId()).userId(p.buyerId()).productId(p.productId()).storeId(p.storeId())
                .buyerDisplayName(buyerName)
                .productName(p.productName()).optionName(p.optionName()).thumbnailUrl(p.thumbnailUrl())
                .confirmedAt(toKst(p.confirmedAt() != null ? p.confirmedAt() : event.occurredAt()))
                .build());
        log.info("리뷰 작성 자격 저장 orderItemId={} userId={} productId={} expiresAt={} eventId={}",
                p.orderItemId(), p.buyerId(), p.productId(), eligibility.getExpiresAt(), event.eventId());
        return true;
    }

    /** 오프셋이 무엇이든 KST 벽시계 시각으로 — DB 는 Asia/Seoul 기준 DATETIME */
    private static LocalDateTime toKst(OffsetDateTime time) {
        return time.atZoneSameInstant(KST).toLocalDateTime();
    }
}
