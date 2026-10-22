package com.sesac.gojigi.user.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Keycloak 토큰 응답 매핑 · 요청 모양 · 오류 변환 (서킷 브레이커 동작은 AOP 라 여기서 보지 않음) */
class KeycloakClientAdapterTest {

    private static final String BASE = "http://keycloak:8080";
    private static final String TOKEN_URL = BASE + "/realms/gotgan/protocol/openid-connect/token";
    private static final String USERS_URL = BASE + "/admin/realms/gotgan/users";

    private MockRestServiceServer server;
    private KeycloakClientAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        KeycloakClientConfig config = new KeycloakClientConfig();
        adapter = new KeycloakClientAdapter(
                config.keycloakClient(builder, BASE, "gotgan", "gotgan-backend", "test-secret"),
                config.keycloakAdminClient(builder, BASE, "gotgan"),
                JsonMapper.builder().build());
    }

    @Test
    @DisplayName("Direct Grant — client 인증은 Basic 헤더, 폼에는 grant_type · 이메일 · 비밀번호만 담고 토큰 응답을 읽는다")
    void passwordGrant_sendsFormAndReadsToken() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic("gotgan-backend:test-secret")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(new LinkedMultiValueMap<>(Map.of(
                        "grant_type", List.of("password"), "username", List.of("buyer1@gotgan.com"),
                        "password", List.of("Dem0!pass")))))
                .andRespond(withSuccess("""
                        {"access_token":"at","expires_in":1800,"refresh_token":"rt","refresh_expires_in":1209600,
                         "token_type":"Bearer","scope":"profile email","unknownField":true}
                        """, MediaType.APPLICATION_JSON));

        KeycloakTokenResponse token = adapter.passwordGrant("buyer1@gotgan.com", "Dem0!pass");

        assertThat(token.accessToken()).isEqualTo("at");
        assertThat(token.expiresIn()).isEqualTo(1800);
        assertThat(token.refreshToken()).isEqualTo("rt");
        assertThat(token.refreshExpiresIn()).isEqualTo(1209600);
        server.verify();
    }

    @Test
    @DisplayName("invalid_grant(비밀번호 불일치 · 없는 계정 · 임시 잠금)는 AUTH_INVALID_CREDENTIALS")
    void passwordGrant_invalidGrant_becomesInvalidCredentials() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid user credentials\"}"));

        assertThatThrownBy(() -> adapter.passwordGrant("buyer1@gotgan.com", "wrong"))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    @Test
    @DisplayName("정지 계정(Account disabled)은 AUTH_ACCOUNT_DISABLED")
    void passwordGrant_accountDisabled_becomesDisabled() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Account disabled\"}"));

        assertThatThrownBy(() -> adapter.passwordGrant("buyer1@gotgan.com", "Dem0!pass"))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
    }

    @Test
    @DisplayName("client 설정 오류(invalid_client)는 EXTERNAL_API_ERROR — 원인은 cause 로 넘겨 핸들러가 한 번만 기록")
    void passwordGrant_invalidClient_becomesExternalApiError() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\",\"error_description\":\"Invalid client credentials\"}"));

        assertThatThrownBy(() -> adapter.passwordGrant("buyer1@gotgan.com", "Dem0!pass"))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("JSON 이 아닌 4xx 응답도 EXTERNAL_API_ERROR")
    void passwordGrant_nonJsonError_becomesExternalApiError() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("not json"));

        assertThatThrownBy(() -> adapter.passwordGrant("buyer1@gotgan.com", "Dem0!pass"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("서킷 폴백 — 연결 실패는 EXTERNAL_API_ERROR(cause 유지), 이미 변환된 도메인 예외는 그대로")
    void tokenFallback_mapsUnavailableAndKeepsBusiness() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, "tokenFallback",
                "buyer1@gotgan.com", "Dem0!pass", new ResourceAccessException("connection refused")))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(ResourceAccessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, "tokenFallback",
                "buyer1@gotgan.com", "Dem0!pass", new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS)))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("client-id · secret 의 특수문자는 URL 인코딩 후 Basic 헤더에 넣는다 (RFC 6749 2.3.1)")
    void keycloakClient_urlEncodesCredentialsInBasicHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer encodedServer = MockRestServiceServer.bindTo(builder).build();
        KeycloakClientConfig config = new KeycloakClientConfig();
        KeycloakClientAdapter encodedAdapter = new KeycloakClientAdapter(
                config.keycloakClient(builder, BASE, "gotgan", "gotgan-backend", "a+b/c:d%"),
                config.keycloakAdminClient(builder, BASE, "gotgan"),
                JsonMapper.builder().build());
        encodedServer.expect(requestTo(TOKEN_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic("gotgan-backend:a%2Bb%2Fc%3Ad%25")))
                .andRespond(withSuccess("""
                        {"access_token":"at","expires_in":1800,"refresh_token":"rt","refresh_expires_in":1209600}
                        """, MediaType.APPLICATION_JSON));

        encodedAdapter.passwordGrant("buyer1@gotgan.com", "Dem0!pass");

        encodedServer.verify();
    }

    private void expectServiceAccountToken() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic("gotgan-backend:test-secret")))
                .andExpect(content().formData(new LinkedMultiValueMap<>(Map.of("grant_type", List.of("client_credentials")))))
                .andRespond(withSuccess("""
                        {"access_token":"sa-token","expires_in":300,"refresh_expires_in":0,"token_type":"Bearer","scope":"profile email"}
                        """, MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("계정 생성 — 서비스 계정 토큰(Bearer)으로 username=email · 비밀번호 · user_id 속성을 보내고 Location 의 ID 를 돌려준다")
    void createUser_sendsUserWithServiceAccountToken() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sa-token"))
                .andExpect(content().json("""
                        {"username":"new1@gotgan.com","email":"new1@gotgan.com","enabled":true,
                         "attributes":{"user_id":["7"]},
                         "credentials":[{"type":"password","value":"Newbie!pass1","temporary":false}]}
                        """, true))
                .andRespond(withStatus(HttpStatus.CREATED).location(URI.create(USERS_URL + "/2439b1b2-0000-4000-8000-000000000007")));

        String keycloakId = adapter.createUser("new1@gotgan.com", "Newbie!pass1", 7L);

        assertThat(keycloakId).isEqualTo("2439b1b2-0000-4000-8000-000000000007");
        server.verify();
    }

    @Test
    @DisplayName("계정 생성 409(User exists with same email)는 AUTH_EMAIL_DUPLICATE")
    void createUser_conflict_becomesEmailDuplicate() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errorMessage\":\"User exists with same email\"}"));

        assertThatThrownBy(() -> adapter.createUser("ghost@gotgan.com", "Ghost!pass1", 8L))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_EMAIL_DUPLICATE);
    }

    @Test
    @DisplayName("계정 생성 400 비밀번호 정책 위반(invalidPassword...)은 AUTH_PASSWORD_POLICY_VIOLATION")
    void createUser_passwordPolicy_becomesPolicyViolation() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error":"invalidPasswordMinSpecialCharsMessage",
                         "error_description":"Invalid password: must contain at least 1 special characters."}
                        """));

        assertThatThrownBy(() -> adapter.createUser("new2@gotgan.com", "abcdefgh", 9L))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
    }

    @Test
    @DisplayName("계정 생성 403(서비스 계정 권한 없음)은 EXTERNAL_API_ERROR — 원인은 cause 로")
    void createUser_forbidden_becomesExternalApiError() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.createUser("new3@gotgan.com", "Newbie!pass1", 10L))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("서비스 계정 토큰 발급 거절(unauthorized_client)은 EXTERNAL_API_ERROR — 계정 생성 요청은 보내지 않는다")
    void createUser_serviceAccountRejected_becomesExternalApiError() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"unauthorized_client\"}"));

        assertThatThrownBy(() -> adapter.createUser("new4@gotgan.com", "Newbie!pass1", 11L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        server.verify();
    }

    @Test
    @DisplayName("계정 생성 응답에 Location 이 없으면 EXTERNAL_API_ERROR")
    void createUser_missingLocation_becomesExternalApiError() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL)).andRespond(withStatus(HttpStatus.CREATED));

        assertThatThrownBy(() -> adapter.createUser("new5@gotgan.com", "Newbie!pass1", 12L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("계정 생성 폴백 — 연결 실패는 EXTERNAL_API_ERROR(cause 유지), 도메인 예외는 그대로")
    void createUserFallback_mapsUnavailableAndKeepsBusiness() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, "createUserFallback",
                "new6@gotgan.com", "Newbie!pass1", 13L, new ResourceAccessException("connection refused")))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(ResourceAccessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, "createUserFallback",
                "new6@gotgan.com", "Newbie!pass1", 13L, new AuthException(AuthErrorCode.AUTH_EMAIL_DUPLICATE)))
                .isInstanceOf(AuthException.class);
    }

    @Test
    @DisplayName("계정 삭제 — 서비스 계정 토큰(Bearer)으로 DELETE /users/{keycloakId}")
    void deleteUser_byKeycloakId_deletes() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "/kc-7"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sa-token"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.deleteUser("kc-7", "new1@gotgan.com");

        server.verify();
    }

    @Test
    @DisplayName("계정 삭제 — keycloak_id 가 없으면(첫 로그인 전) username 정확 일치로 찾아 삭제")
    void deleteUser_withoutKeycloakId_findsByUsername() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "?username=wlsgksvheh%2Bbuyer2%40gmail.com&exact=true"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"kc-5\",\"username\":\"wlsgksvheh+buyer2@gmail.com\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS_URL + "/kc-5"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.deleteUser(null, "wlsgksvheh+buyer2@gmail.com");

        server.verify();
    }

    @Test
    @DisplayName("계정 삭제 — 검색 결과가 없거나 이미 지워져 404 면 성공으로 본다")
    void deleteUser_alreadyGone_succeeds() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "?username=gone%40gotgan.com&exact=true")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        adapter.deleteUser(null, "gone@gotgan.com");
        server.verify();

        server.reset();
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "/kc-gone")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        adapter.deleteUser("kc-gone", "gone@gotgan.com");
        server.verify();
    }

    @Test
    @DisplayName("계정 삭제 403(서비스 계정 권한 없음)은 EXTERNAL_API_ERROR — 원인은 cause 로")
    void deleteUser_forbidden_becomesExternalApiError() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "/kc-7")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.deleteUser("kc-7", "new1@gotgan.com"))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("계정 삭제 폴백 — 연결 실패는 EXTERNAL_API_ERROR(cause 유지)")
    void deleteUserFallback_mapsUnavailable() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, "deleteUserFallback",
                "kc-7", "new1@gotgan.com", new ResourceAccessException("connection refused")))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(ResourceAccessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    private static final String LOGOUT_URL = BASE + "/realms/gotgan/protocol/openid-connect/logout";

    @Test
    @DisplayName("로그아웃 — client 인증은 Basic 헤더, 폼에는 refresh_token 만 보낸다 (204)")
    void logout_sendsRefreshToken() {
        server.expect(requestTo(LOGOUT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic("gotgan-backend:test-secret")))
                .andExpect(content().formData(new LinkedMultiValueMap<>(Map.of("refresh_token", List.of("rt-1")))))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.logout("rt-1");

        server.verify();
    }

    @Test
    @DisplayName("로그아웃 — 만료 · 잘못된 토큰(invalid_grant)은 이미 로그아웃된 것으로 보고 성공")
    void logout_invalidGrant_succeeds() {
        server.expect(requestTo(LOGOUT_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid refresh token\"}"));

        adapter.logout("expired");

        server.verify();
    }

    @Test
    @DisplayName("로그아웃 — client 설정 오류(invalid_client)는 EXTERNAL_API_ERROR")
    void logout_invalidClient_becomesExternalApiError() {
        server.expect(requestTo(LOGOUT_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(() -> adapter.logout("rt-1"))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(HttpClientErrorException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("로그아웃 폴백 — 연결 실패는 EXTERNAL_API_ERROR(cause 유지)")
    void logoutFallback_mapsUnavailable() {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, "logoutFallback",
                "rt-1", new ResourceAccessException("connection refused")))
                .isInstanceOf(BusinessException.class)
                .hasCauseInstanceOf(ResourceAccessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("재발급 — grant_type=refresh_token 폼, invalid_grant(만료 · 로그아웃)는 AUTH_SESSION_EXPIRED")
    void refreshGrant_sendsRefreshTokenAndMapsInvalidGrant() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(content().formData(new LinkedMultiValueMap<>(Map.of(
                        "grant_type", List.of("refresh_token"), "refresh_token", List.of("rt-1")))))
                .andRespond(withSuccess("""
                        {"access_token":"at","expires_in":1800,"refresh_token":"rt-2","refresh_expires_in":1209600}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Session not active\"}"));

        assertThat(adapter.refreshGrant("rt-1").refreshToken()).isEqualTo("rt-2");
        assertThatThrownBy(() -> adapter.refreshGrant("rt-1"))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_SESSION_EXPIRED);
    }

    @Test
    @DisplayName("소셜 code 교환 — authorization_code · PKCE verifier · redirect_uri, invalid_grant 는 AUTH_SOCIAL_LOGIN_FAILED")
    void authorizationCodeGrant_sendsPkceAndMapsInvalidGrant() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, basic("gotgan-backend:test-secret")))
                .andExpect(content().formData(new LinkedMultiValueMap<>(Map.of(
                        "grant_type", List.of("authorization_code"), "code", List.of("c-1"),
                        "code_verifier", List.of("v-1"), "redirect_uri", List.of("https://front/cb")))))
                .andRespond(withSuccess("""
                        {"access_token":"at","expires_in":1800,"refresh_token":"rt","refresh_expires_in":1209600}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Code not valid\"}"));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"unauthorized_client\"}"));

        assertThat(adapter.authorizationCodeGrant("c-1", "v-1", "https://front/cb").accessToken()).isEqualTo("at");
        assertThatThrownBy(() -> adapter.authorizationCodeGrant("c-1", "v-1", "https://front/cb"))
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_SOCIAL_LOGIN_FAILED);
        assertThatThrownBy(() -> adapter.authorizationCodeGrant("c-1", "v-1", "https://front/cb"))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("user_id 속성 저장 — 현재 사용자 표현을 받아 attributes 에 user_id 만 더해 PUT (다른 값은 그대로)")
    void setUserIdAttribute_mergesAttributesAndPuts() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "/kc-7"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":"kc-7","username":"k@e.com","email":"k@e.com","firstName":"카카오","enabled":true,
                         "attributes":{"locale":["ko"]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS_URL + "/kc-7"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sa-token"))
                .andExpect(content().json("""
                        {"id":"kc-7","username":"k@e.com","email":"k@e.com","firstName":"카카오","enabled":true,
                         "attributes":{"locale":["ko"],"user_id":["7"]}}
                        """, true))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.setUserIdAttribute("kc-7", 7L);

        server.verify();
    }

    @Test
    @DisplayName("user_id 속성 저장 — 4xx 는 EXTERNAL_API_ERROR, 연결 실패 폴백도 EXTERNAL_API_ERROR")
    void setUserIdAttribute_failures() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "/kc-7")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> adapter.setUserIdAttribute("kc-7", 7L))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        for (String fallback : List.of("refreshGrantFallback", "setUserIdAttributeFallback")) {
            Object[] args = fallback.equals("refreshGrantFallback")
                    ? new Object[] { "rt", new ResourceAccessException("down") }
                    : new Object[] { "kc-7", 7L, new ResourceAccessException("down") };
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, fallback, args))
                    .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        }
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, "authorizationCodeGrantFallback",
                "c", "v", "r", new ResourceAccessException("down")))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("비밀번호 변경 — reset-password 에 임시 아닌 password 자격 증명을 보낸다 (시드 회원은 username 으로 찾음)")
    void resetPassword_sendsCredential() {
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "?username=wlsgksvheh%2Bbuyer2%40gmail.com&exact=true"))
                .andRespond(withSuccess("[{\"id\":\"kc-5\",\"username\":\"wlsgksvheh+buyer2@gmail.com\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(USERS_URL + "/kc-5/reset-password"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sa-token"))
                .andExpect(content().json("{\"type\":\"password\",\"value\":\"New!pass2\",\"temporary\":false}", true))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        adapter.resetPassword(null, "wlsgksvheh+buyer2@gmail.com", "New!pass2");

        server.verify();
    }

    @Test
    @DisplayName("비밀번호 변경 — 정책 위반 400 은 AUTH_PASSWORD_POLICY_VIOLATION, 그 외 4xx · 계정 없음 · 연결 실패는 EXTERNAL_API_ERROR")
    void resetPassword_failures() {
        // MockRestServiceServer 는 요청이 나간 뒤 기대를 추가할 수 없어 세 번 호출의 기대를 먼저 모두 등록
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "/kc-5/reset-password"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalidPasswordMinLengthMessage\",\"error_description\":\"Invalid password: minimum length 8.\"}"));
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "/kc-5/reset-password")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectServiceAccountToken();
        server.expect(requestTo(USERS_URL + "?username=gone%40gotgan.com&exact=true")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.resetPassword("kc-5", "b@gotgan.com", "New!pass2"))
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
        assertThatThrownBy(() -> adapter.resetPassword("kc-5", "b@gotgan.com", "New!pass2"))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> adapter.resetPassword(null, "gone@gotgan.com", "New!pass2"))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(adapter, "resetPasswordFallback",
                "kc-5", "b@gotgan.com", "New!pass2", new ResourceAccessException("down")))
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        server.verify();
    }
}
