package com.sesac.gojigi.gateway.config;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import java.net.InetSocketAddress;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 지표(/actuator/prometheus)는 클러스터 노드 대역에서만 — Prometheus 파드만 읽으면 된다 (K-3).
 * 외부 요청은 NLB 가 자기 IP(퍼블릭 서브넷)로 바꿔 넘기므로(preserve_client_ip=false) 프라이빗 대역에 들지 않는다.
 * 원래 IP 를 보존하도록 바꿔도 공인 IP 라 마찬가지로 막힌다.
 * TCP 접속 IP(getRemoteAddress)만 본다 — X-Forwarded-For 같은 헤더는 지어낼 수 있으므로 쓰지 않는다.
 * 대역 판정은 Netty IpSubnetFilterRule (Spring Security IpAddressMatcher 는 서블릿 API 를 참조해 WebFlux 에서 못 씀).
 * 대역 = infra/terraform/vpc/main.tf private_subnets (상시 유지 — 매일 클러스터를 다시 만들어도 그대로). 바꾸면 app.metrics.allowed-cidrs 도 같이.
 */
@Component
public class MetricsAccessManager implements ReactiveAuthorizationManager<AuthorizationContext> {

    private final List<IpSubnetFilterRule> allowed;

    public MetricsAccessManager(@Value("${app.metrics.allowed-cidrs}") List<String> allowedCidrs) {
        this.allowed = allowedCidrs.stream().map(String::trim).map(MetricsAccessManager::subnet).toList();
    }

    /** "10.0.11.0/24" · "::1/128" — 형식이 틀리면 기동 실패 (잘못된 설정으로 지표가 조용히 막히거나 열리지 않게) */
    private static IpSubnetFilterRule subnet(String cidr) {
        int slash = cidr.lastIndexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("app.metrics.allowed-cidrs 는 CIDR(주소/길이) 형식이어야 합니다: " + cidr);
        }
        return new IpSubnetFilterRule(cidr.substring(0, slash), Integer.parseInt(cidr.substring(slash + 1)), IpFilterRuleType.ACCEPT);
    }

    @Override
    public Mono<AuthorizationResult> authorize(Mono<Authentication> authentication, AuthorizationContext context) {
        return Mono.just(new AuthorizationDecision(isAllowed(context.getExchange().getRequest().getRemoteAddress())));
    }

    boolean isAllowed(InetSocketAddress remote) {
        if (remote == null || remote.getAddress() == null) {
            return false;   // 접속 IP 를 모르면 거절
        }
        return allowed.stream().anyMatch(rule -> rule.matches(remote));
    }
}
