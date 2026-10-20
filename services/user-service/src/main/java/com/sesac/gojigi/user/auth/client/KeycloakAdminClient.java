package com.sesac.gojigi.user.auth.client;

import com.sesac.gojigi.user.auth.client.dto.KeycloakUserCreateRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Keycloak Admin REST API ({base-url}/admin/realms/gotgan). gotgan-backend 서비스 계정 토큰(Bearer)으로 호출한다
 * — 권한은 realm-management 의 manage-users · view-realm (keycloak README).
 * 직접 쓰지 않고 KeycloakClientAdapter(서킷 브레이커 "keycloak")를 거친다.
 */
@HttpExchange("/users")
public interface KeycloakAdminClient {

    /** 계정 생성 — 201 + Location: .../users/{keycloakId}. 같은 username 이 있으면 409 */
    @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> createUser(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody KeycloakUserCreateRequest user);
}
