package com.sesac.gojigi.user.auth.client;

import com.sesac.gojigi.user.auth.client.dto.KeycloakUserCreateRequest;
import com.sesac.gojigi.user.auth.client.dto.KeycloakUserSummary;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;

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

    /** username(= email) 정확히 일치 검색 — keycloak_id 가 비어 있는 회원(첫 로그인 전 시드 계정)용 */
    @GetExchange
    List<KeycloakUserSummary> findByUsername(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam("username") String username, @RequestParam("exact") boolean exact);

    /** 사용자 조회 — 수정 전 전체 표현을 받아 필요한 값만 바꿔 다시 보낸다 (PUT 은 보낸 값으로 덮어씀) */
    @GetExchange("/{keycloakId}")
    Map<String, Object> getUser(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("keycloakId") String keycloakId);

    /** 사용자 수정 — 204 */
    @PutExchange(url = "/{keycloakId}", contentType = MediaType.APPLICATION_JSON_VALUE)
    void updateUser(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("keycloakId") String keycloakId, @RequestBody Map<String, Object> user);

    /** 비밀번호 설정 — 204. body {type: password, value, temporary: false}. 정책 위반은 400 */
    @PutExchange(url = "/{keycloakId}/reset-password", contentType = MediaType.APPLICATION_JSON_VALUE)
    void resetPassword(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("keycloakId") String keycloakId, @RequestBody Map<String, Object> credential);

    /** 계정 삭제 — 204. 세션 · Refresh Token 도 함께 무효. 없으면 404 */
    @DeleteExchange("/{keycloakId}")
    void deleteUser(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable("keycloakId") String keycloakId);
}
