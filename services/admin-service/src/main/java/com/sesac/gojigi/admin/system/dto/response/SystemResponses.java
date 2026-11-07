package com.sesac.gojigi.admin.system.dto.response;

import com.sesac.gojigi.admin.system.entity.ChaosEvent;
import com.sesac.gojigi.admin.system.entity.KillScope;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/** 장애 시연 API 응답 (docs/api/admin.md 7절) — 시각은 KST 오프셋 */
public final class SystemResponses {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private SystemResponses() {
    }

    static OffsetDateTime kst(LocalDateTime at) {
        return at == null ? null : at.atZone(KST).toOffsetDateTime();
    }

    /** GET /status — features 는 서버가 의존 관계로 계산 */
    public record Status(boolean chaosEnabled, OffsetDateTime serverTime, List<ServiceStatus> services, List<FeatureStatus> features) {
    }

    /** ready = 지워지는 중이 아닌 Ready 파드 수. pods 에는 지워지는 중(terminating)인 파드도 포함 */
    public record ServiceStatus(String name, boolean killable, int desired, int ready, ServiceState state, List<PodItem> pods) {
    }

    public record PodItem(String name, String node, String phase, boolean ready, int restarts, long ageSeconds, boolean terminating) {
    }

    /** 사용자 기능 — causes = 문제(UP 이 아닌) 원인 서비스 */
    public record FeatureStatus(String key, String label, ServiceState state, List<String> causes) {
    }

    /** GET /metrics — 서비스 순서는 status 와 같음 */
    public record Metrics(List<ServiceMetric> services) {
    }

    /** gateway 라우트 지표 기준(1분 rate). errorRate = 5xx ÷ 전체(0 ~ 1), 요청 0 · 조회 실패면 null */
    public record ServiceMetric(String name, Double requestsPerSecond, Double errorRate) {
    }

    /** POST .../kill → 202 */
    public record Killed(Long eventId, List<String> deletedPods) {
    }

    /** GET /events — 최신순 20건 */
    public record EventItem(Long eventId, String service, KillScope scope, String reason, List<String> deletedPods, Long actorId,
            OffsetDateTime killedAt, OffsetDateTime recoveredAt, Long recoverySeconds) {

        public static EventItem of(ChaosEvent e) {
            return new EventItem(e.getId(), e.getServiceName(), e.getScope(), e.getReason(), e.deletedPodList(), e.getActorId(),
                    kst(e.getKilledAt()), kst(e.getRecoveredAt()), e.recoverySeconds());
        }
    }
}
