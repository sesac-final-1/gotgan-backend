package com.sesac.gojigi.user.point.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 포인트 사용 sagaId 표식 — 같은 sagaId 의 사용과 사용 취소 중 먼저 온 쪽이 남긴다 (V4 point_use_saga). INSERT 전용.
 * 같은 sagaId 두 번째 INSERT 는 uk_point_use_saga_saga_id 충돌 — 겹친 쪽을 알아내는 데 쓴다.
 */
@Getter
@Entity
@Table(name = "point_use_saga")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointUseSaga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "point_use_saga_id")
    private Long id;

    @Column(name = "saga_id", nullable = false, length = 64)
    private String sagaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PointUseSagaStatus status;

    private PointUseSaga(String sagaId, PointUseSagaStatus status) {
        this.sagaId = sagaId;
        this.status = status;
    }

    public static PointUseSaga used(String sagaId) {
        return new PointUseSaga(sagaId, PointUseSagaStatus.USED);
    }

    public static PointUseSaga released(String sagaId) {
        return new PointUseSaga(sagaId, PointUseSagaStatus.RELEASED);
    }

    public boolean isReleased() {
        return status == PointUseSagaStatus.RELEASED;
    }
}
