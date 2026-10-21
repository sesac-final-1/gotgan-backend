package com.sesac.gojigi.common.web.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * /actuator/** 요청(쿠버네티스 readiness · liveness 프로브, Prometheus 수집)은 관측하지 않는다 — Zipkin 트레이스로 남기지 않음 (10/21 박지연 제안).
 * 사용자 요청이 없어도 프로브만으로 분당 수백 span 이 쌓여 Zipkin 메모리 한도(MEM_MAX_SPANS)를 몇 시간 만에 채우고 실제 요청 트레이스를 밀어낸다.
 * 응답은 그대로 나간다 — 기록만 빠진다. 같은 관측에서 나오는 http.server.requests 지표에서도 /actuator 는 빠진다(RED 대시보드에 프로브가 섞이지 않음).
 * 서비스가 살아 있는지는 Prometheus up · kubectl get pods 로 본다.
 */
public class ActuatorObservationPredicate implements ObservationPredicate {

    private static final String ACTUATOR = "/actuator";

    @Override
    public boolean test(String name, Observation.Context context) {
        if (context instanceof ServerRequestObservationContext serverContext) {
            String uri = serverContext.getCarrier().getRequestURI();
            return !(uri.equals(ACTUATOR) || uri.startsWith(ACTUATOR + "/"));
        }
        return true;
    }
}
