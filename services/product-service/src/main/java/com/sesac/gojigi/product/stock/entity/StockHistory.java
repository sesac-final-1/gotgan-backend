package com.sesac.gojigi.product.stock.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * 재고 원장 (productdb stock_history, INSERT 전용) — Saga 멱등용. uk(type, ref_key, product_option_id) 가 같은 처리 두 번을 막는다.
 * 판매자 직접 재고 수정은 기록하지 않는다. 옵션은 ID 로만 든다 (원장이 옵션을 읽을 일이 없음).
 */
@Getter
@Entity
@Table(name = "stock_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stock_history_id")
    private Long id;

    @Column(name = "product_option_id", nullable = false, updatable = false)
    private Long productOptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private StockType type;

    /** RESERVE · RELEASE: sagaId / RESTORE: claim-{claimId} */
    @Column(name = "ref_key", nullable = false, length = 64, updatable = false)
    private String refKey;

    @Column(name = "quantity", nullable = false, columnDefinition = "INT UNSIGNED", updatable = false)
    private int quantity;

    /** DB 기본값 — BaseTimeEntity 는 updated_at 도 있어 쓰지 않는다 (원장은 수정 없음) */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    public static StockHistory of(Long productOptionId, StockType type, String refKey, int quantity) {
        StockHistory history = new StockHistory();
        history.productOptionId = productOptionId;
        history.type = type;
        history.refKey = refKey;
        history.quantity = quantity;
        return history;
    }
}
