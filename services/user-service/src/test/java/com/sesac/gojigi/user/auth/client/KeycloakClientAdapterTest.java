package com.sesac.gojigi.user.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.common.exception.CommonErrorCode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Keycloak 토큰 응답 매핑 · 요청 모양 · 오류 변환 (서킷 브레이커 동작은 AOP 라 여기서 보지 않음) */
class KeycloakClientAdapterTest {

    private static final String BASE = "http://keycloak:8080";
    private static final String TOKEN_URL = BASE + "/realms/gotgan/protocol/openid-connect/token";

    private MockRestServiceServer server;
    private KeycloakClientAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new KeycloakClientAdapter(new KeycloakClientConfig().keycloakClient(builder, BASE, "gotgan"),
                JsonMapper.builder().build(), "gotgan-backend", "test-secret");
    }

    @Test
    @DisplayName("Direct Grant — 폼에 grant_type · client 인증 · 이메일 · 비밀번호를 담고 토큰 응답을 읽는다")
    void passwordGrant_sendsFormAndReadsToken() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "password", "client_id", "gotgan-backend", "client_secret", "test-secret",
                        "username", "buyer1@gotgan.com", "password", "Dem0!pass")))
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
}
