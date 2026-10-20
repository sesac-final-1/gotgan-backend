package com.sesac.gojigi.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.dto.request.LoginRequest;
import com.sesac.gojigi.user.auth.dto.request.SignupRequest;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.common.exception.CommonErrorCode;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
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
        // 트랜잭션 템플릿은 콜백을 그대로 실행 (중복 이메일처럼 트랜잭션 전에 끝나는 경우가 있어 lenient)
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
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

    // ── 회원가입

    private static final SignupRequest SIGNUP =
            new SignupRequest("New1@Gotgan.com", "Newbie!pass1", " 새회원 ", "01012345678");

    /** saveAndFlush 가 IDENTITY 로 id 를 채운 것처럼 — 저장된 객체를 돌려준다 */
    private User[] givenSavedAs(long id) {
        User[] saved = new User[1];
        given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved[0], "id", id);
            return saved[0];
        });
        return saved;
    }

    private void givenNewMemberToken(long userId) {
        given(keycloakClientAdapter.passwordGrant("new1@gotgan.com", "Newbie!pass1")).willReturn(new KeycloakTokenResponse(
                AccessTokenClaimsTest.token("{\"sub\":\"kc-" + userId + "\",\"user_id\":" + userId + ",\"roles\":[\"BUYER\"]}"),
                1800, "refresh", 1209600));
    }

    @Test
    @DisplayName("가입 — 정규화한 값으로 회원 행 저장 → Keycloak 계정 생성 → keycloak_id 연결 → 자동 로그인")
    void signup_success_createsRowAndAccountThenLogsIn() {
        // given
        given(userRepository.existsByEmail("new1@gotgan.com")).willReturn(false);
        User[] saved = givenSavedAs(7L);
        given(keycloakClientAdapter.createUser("new1@gotgan.com", "Newbie!pass1", 7L)).willReturn("kc-7");
        given(userRepository.findById(7L)).willAnswer(invocation -> Optional.of(saved[0]));
        givenNewMemberToken(7L);
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willAnswer(invocation -> Optional.of(saved[0]));

        // when
        SignupResult result = authService.signup(SIGNUP);

        // then
        ArgumentCaptor<User> row = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(row.capture());
        assertThat(row.getValue().getEmail()).isEqualTo("new1@gotgan.com");
        assertThat(row.getValue().getName()).isEqualTo("새회원");
        assertThat(row.getValue().getPhone()).isEqualTo("010-1234-5678");
        assertThat(row.getValue().getSignupProvider()).isEqualTo(SignupProvider.LOCAL);
        assertThat(saved[0].getKeycloakId()).isEqualTo("kc-7");
        assertThat(saved[0].getLastLoginAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.login().response().user().roles()).containsExactly("BUYER");
        assertThat(result.login().refreshToken()).isEqualTo("refresh");
    }

    @Test
    @DisplayName("이미 가입된 이메일(탈퇴 회원 포함)은 AUTH_EMAIL_DUPLICATE — 행 · 계정을 만들지 않는다")
    void signup_duplicateEmail_throwsWithoutCreating() {
        // given
        given(userRepository.existsByEmail("new1@gotgan.com")).willReturn(true);

        // when · then
        assertThatThrownBy(() -> authService.signup(SIGNUP))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_EMAIL_DUPLICATE);
        verify(userRepository, never()).saveAndFlush(any());
        verify(keycloakClientAdapter, never()).createUser(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("비밀번호가 이메일과 같으면(대소문자 무시) AUTH_PASSWORD_POLICY_VIOLATION — realm 정책 notUsername")
    void signup_passwordEqualsEmail_throwsPolicyViolation() {
        // given
        SignupRequest request = new SignupRequest("pw.x@gotgan.com", "PW.X@gotgan.com", "새회원", "010-1234-5678");

        // when · then
        assertThatThrownBy(() -> authService.signup(request))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("동시 가입으로 저장 시 유니크 제약 위반이면 AUTH_EMAIL_DUPLICATE")
    void signup_concurrentInsert_throwsDuplicate() {
        // given
        given(userRepository.existsByEmail("new1@gotgan.com")).willReturn(false);
        given(userRepository.saveAndFlush(any(User.class))).willThrow(new DataIntegrityViolationException("uk_users_email"));

        // when · then
        assertThatThrownBy(() -> authService.signup(SIGNUP))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_EMAIL_DUPLICATE);
        verify(keycloakClientAdapter, never()).createUser(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Keycloak 계정 생성이 실패하면 회원 행을 지우고(보상) 같은 예외를 던진다 — 로그인하지 않는다")
    void signup_keycloakFails_deletesRowAndRethrows() {
        // given
        given(userRepository.existsByEmail("new1@gotgan.com")).willReturn(false);
        givenSavedAs(8L);
        BusinessException failure = new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버에 연결할 수 없습니다.");
        given(keycloakClientAdapter.createUser("new1@gotgan.com", "Newbie!pass1", 8L)).willThrow(failure);

        // when · then
        assertThatThrownBy(() -> authService.signup(SIGNUP)).isSameAs(failure);
        verify(userRepository).deleteById(8L);
        verify(keycloakClientAdapter, never()).passwordGrant(anyString(), anyString());
    }

    @Test
    @DisplayName("keycloak_id 연결이 실패해도 가입은 유지 — 자동 로그인에서 연결된다")
    void signup_linkFails_keepsSignupAndLinksOnLogin() {
        // given
        given(userRepository.existsByEmail("new1@gotgan.com")).willReturn(false);
        User[] saved = givenSavedAs(7L);
        given(keycloakClientAdapter.createUser("new1@gotgan.com", "Newbie!pass1", 7L)).willReturn("kc-7");
        willThrow(new IllegalStateException("db down")).given(userRepository).findById(7L);
        givenNewMemberToken(7L);
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willAnswer(invocation -> Optional.of(saved[0]));

        // when
        SignupResult result = authService.signup(SIGNUP);

        // then
        assertThat(result.login()).isNotNull();
        assertThat(saved[0].getKeycloakId()).isEqualTo("kc-7");   // 로그인(recordLogin)이 연결
        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("자동 로그인이 실패해도 가입은 완료 — login 없이 돌려준다")
    void signup_autoLoginFails_returnsWithoutLogin() {
        // given
        given(userRepository.existsByEmail("new1@gotgan.com")).willReturn(false);
        User[] saved = givenSavedAs(7L);
        given(keycloakClientAdapter.createUser("new1@gotgan.com", "Newbie!pass1", 7L)).willReturn("kc-7");
        given(userRepository.findById(7L)).willAnswer(invocation -> Optional.of(saved[0]));
        given(keycloakClientAdapter.passwordGrant("new1@gotgan.com", "Newbie!pass1"))
                .willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR));

        // when
        SignupResult result = authService.signup(SIGNUP);

        // then
        assertThat(result.userId()).isEqualTo(7L);
        assertThat(result.login()).isNull();
        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("이메일 중복 확인 — 행이 없으면 사용 가능")
    void isEmailAvailable_reflectsExistence() {
        // given
        given(userRepository.existsByEmail("new1@gotgan.com")).willReturn(false);
        given(userRepository.existsByEmail("seller1@gotgan.com")).willReturn(true);

        // when · then
        assertThat(authService.isEmailAvailable("new1@gotgan.com")).isTrue();
        assertThat(authService.isEmailAvailable("seller1@gotgan.com")).isFalse();
    }
}
