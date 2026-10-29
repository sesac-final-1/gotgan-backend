package com.sesac.gojigi.product.store.service;

import com.sesac.gojigi.product.common.event.EventEnvelope;
import com.sesac.gojigi.product.common.event.ProcessedEvent;
import com.sesac.gojigi.product.common.event.ProcessedEventRepository;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import com.sesac.gojigi.product.store.dto.event.StoreStatusChangedPayload;
import com.sesac.gojigi.product.store.entity.StoreStatus;
import com.sesac.gojigi.product.store.repository.StoreStatusRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * StoreStatusChanged 반영 (담당 박지연, PRD-12) — 스토어 상태 스냅샷 → 그 스토어 상품의 is_store_active (목록 · 검색 · 구매 가능 판단).
 * SQS 표준 큐는 순서를 보장하지 않으므로 스냅샷보다 오래된 이벤트는 무시한다 (docs/events.md 순서 규칙 A안).
 * 상품이 하나도 없어도 스냅샷은 남겨, 그 뒤 등록하는 상품이 이 상태로 시작한다 (SellerProductService.create).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreStatusService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul"); // DB DATETIME 은 Asia/Seoul (컨벤션 3-5)

    private final StoreStatusRepository storeStatusRepository;
    private final ProductRepository productRepository;
    private final ProcessedEventRepository processedEventRepository;

    public enum Result { APPLIED, STALE_IGNORED, DUPLICATE }

    @Transactional
    public Result applyStatusChanged(EventEnvelope<StoreStatusChangedPayload> event) {
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return Result.DUPLICATE;
        }
        Long storeId = event.payload().storeId();
        String status = event.payload().status();
        LocalDateTime occurredAt = event.occurredAt().atZoneSameInstant(KST).toLocalDateTime();

        Optional<StoreStatus> current = storeStatusRepository.findForUpdate(storeId);
        boolean newer = current.map(s -> s.applyIfNewer(status, occurredAt)).orElse(true);
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        if (!newer) {
            log.info("옛 StoreStatusChanged 무시 - storeId={}, status={}, occurredAt={}, eventId={}",
                    storeId, status, event.occurredAt(), event.eventId());
            return Result.STALE_IGNORED;
        }
        if (current.isEmpty()) {
            // 첫 이벤트 — 같은 스토어 첫 이벤트가 동시에 오면 한쪽이 PK 위반으로 실패하고, 재전달 때 잠금 조회로 다시 비교한다
            storeStatusRepository.saveAndFlush(StoreStatus.of(storeId, status, occurredAt));
        }
        int products = productRepository.applyStoreStatus(storeId, StoreStatus.isActive(status), occurredAt);
        log.info("스토어 상태 반영 - storeId={}, status={}, products={}", storeId, status, products);
        return Result.APPLIED;
    }
}
