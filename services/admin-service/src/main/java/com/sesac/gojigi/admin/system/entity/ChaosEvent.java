package com.sesac.gojigi.admin.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** 장애 시연 내리기 1번 = 1행. 파드를 지운 뒤 남기고, 복구 시각은 상태 조회가 처음 본 때 한 번만 채운다 */
@Getter
@Entity
@Table(name = "chaos_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChaosEvent {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chaos_event_id")
    private Long id;

    @Column(name = "service_name", nullable = false, length = 50)
    private String serviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 10)
    private KillScope scope;

    @Column(name = "reason")
    private String reason;

    @Column(name = "deleted_pods", nullable = false, length = 1000)
    private String deletedPods;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "killed_at", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime killedAt;

    @Column(name = "recovered_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime recoveredAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private ChaosEvent(String serviceName, KillScope scope, String reason, List<String> deletedPods, Long actorId, LocalDateTime killedAt) {
        this.serviceName = serviceName;
        this.scope = scope;
        this.reason = reason;
        this.deletedPods = String.join(",", deletedPods);
        this.actorId = actorId;
        this.killedAt = killedAt;
    }

    public static ChaosEvent of(String serviceName, KillScope scope, String reason, List<String> deletedPods, Long actorId,
            LocalDateTime killedAt) {
        return new ChaosEvent(serviceName, scope, reason, deletedPods, actorId, killedAt);
    }

    public boolean recovered() {
        return recoveredAt != null;
    }

    public void markRecovered(LocalDateTime at) {
        if (recoveredAt == null) {
            recoveredAt = at;
        }
    }

    /** 복구까지 걸린 초 — 복구 전이면 null */
    public Long recoverySeconds() {
        return recoveredAt == null ? null : Duration.between(killedAt.atZone(KST), recoveredAt.atZone(KST)).toSeconds();   // 두 시각 모두 KST(ClockConfig)
    }

    public List<String> deletedPodList() {
        return Arrays.stream(deletedPods.split(",")).filter(s -> !s.isBlank()).toList();
    }
}
