package com.sesac.gojigi.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.dto.request.LoginRequest;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), SEOUL);

    @Mock
    private KeycloakClientAdapter keycloakClientAdapter;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(keycloakClientAdapter, userRepository, transactionTemplate,
                JsonMapper.builder().build(), CLOCK);
        // 트랜잭션 템플릿은 콜백을 그대로 실행
        given(transactionTemplate.execute(any())).willAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
    }

    private static User user(long id) {
        User user = User.signUp("seller1@gotgan.com", "판매자1", null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private void givenKeycloakToken(String payload) {
        given(keycloakClientAdapter.passwordGrant("seller1@gotgan.com", "Dem0!pass"))
                .willReturn(new KeycloakTokenResponse(AccessTokenClaimsTest.token(payload), 1800, "refresh", 1209600));
    }

    @Test
    @DisplayName("로그인하면 토큰 · 회원 정보를 돌려주고 keycloak_id 연결 · 마지막 로그인을 기록한다")
    void login_success_linksKeycloakAndRecordsLogin() {
        // given
        User user = user(2L);
        givenKeycloakToken("{\"sub\":\"kc-2\",\"user_id\":2,\"store_id\":1,\"roles\":[\"BUYER\",\"SELLER\",\"offline_access\"]}");
        given(userRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(user));

        // when
        LoginResult result = authService.login(new LoginRequest("seller1@gotgan.com", "Dem0!pass"));

        // then
        assertThat(result.refreshToken()).isEqualTo("refresh");
        assertThat(result.refreshExpiresIn()).isEqualTo(1209600);
        assertThat(result.response().expiresIn()).isEqualTo(1800);
        assertThat(result.response().user().userId()).isEqualTo(2L);
        assertThat(result.response().user().name()).isEqualTo("판매자1");
        assertThat(result.response().user().roles()).containsExactly("BUYER", "SELLER");
        assertThat(result.response().user().storeId()).isEqualTo(1L);
        assertThat(user.getKeycloakId()).isEqualTo("kc-2");
        assertThat(user.getLastLoginAt()).isEqualTo(LocalDateTime.now(CLOCK));
    }

    @Test
    @DisplayName("이미 연결된 keycloak_id 는 바꾸지 않는다")
    void login_whenAlreadyLinked_keepsKeycloakId() {
        // given
        User user = user(2L);
        user.linkKeycloak("kc-original");
        givenKeycloakToken("{\"sub\":\"kc-other\",\"user_id\":2,\"roles\":[\"BUYER\"]}");
        given(userRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(user));

        // when
        authService.login(new LoginRequest("seller1@gotgan.com", "Dem0!pass"));

        // then
        assertThat(user.getKeycloakId()).isEqualTo("kc-original");
    }

    @Test
    @DisplayName("토큰에 user_id 가 없으면 연결되지 않은 계정")
    void login_whenUserIdMissing_throwsNotLinked() {
        // given
        givenKeycloakToken("{\"sub\":\"kc-2\",\"roles\":[\"BUYER\"]}");

        // when · then
        assertThatThrownBy(() -> authService.login(new LoginRequest("seller1@gotgan.com", "Dem0!pass")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_NOT_LINKED);
    }

    @Test
    @DisplayName("userdb 에 회원이 없으면 연결되지 않은 계정")
    void login_whenUserRowMissing_throwsNotLinked() {
        // given
        givenKeycloakToken("{\"sub\":\"kc-2\",\"user_id\":99,\"roles\":[\"BUYER\"]}");
        given(userRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> authService.login(new LoginRequest("seller1@gotgan.com", "Dem0!pass")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_NOT_LINKED);
    }

    @Test
    @DisplayName("userdb 에서 정지된 회원은 Keycloak 동기화가 늦어도 로그인할 수 없다")
    void login_whenSuspendedInUserDb_throwsDisabled() {
        // given
        User user = user(2L);
        user.suspend("부정 거래", LocalDateTime.now(CLOCK));
        givenKeycloakToken("{\"sub\":\"kc-2\",\"user_id\":2,\"roles\":[\"BUYER\"]}");
        given(userRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(user));

        // when · then
        assertThatThrownBy(() -> authService.login(new LoginRequest("seller1@gotgan.com", "Dem0!pass")))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_DISABLED);
    }
}
