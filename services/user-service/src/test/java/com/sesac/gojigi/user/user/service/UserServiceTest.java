package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.common.exception.CommonErrorCode;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
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

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthService authService;

    @Mock
    private TransactionTemplate transactionTemplate;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, authService, transactionTemplate, CLOCK);
        // 트랜잭션 템플릿은 콜백을 그대로 실행
        given(transactionTemplate.execute(any())).willAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
    }

    private static User member(long id, String keycloakId) {
        User user = User.signUp("new1@gotgan.com", "새회원", null, "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", id);
        user.linkKeycloak(keycloakId);
        return user;
    }

    @Test
    @DisplayName("탈퇴 — deleted_at 기록(이메일은 그대로 → 재가입 불가) 후 Keycloak 계정 삭제")
    void withdraw_softDeletesThenDeletesAccount() {
        // given
        User user = member(7L, "kc-7");
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(user));

        // when
        userService.withdraw(7L);

        // then
        assertThat(user.getDeletedAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(user.getEmail()).isEqualTo("new1@gotgan.com");
        verify(authService).deleteAccount("kc-7", "new1@gotgan.com");
    }

    @Test
    @DisplayName("없는 회원 · 이미 탈퇴한 회원은 USER_NOT_FOUND — Keycloak 은 건드리지 않는다")
    void withdraw_notFound_throws() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> userService.withdraw(7L))
                .isInstanceOf(UserException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_NOT_FOUND);
        verify(authService, never()).deleteAccount(any(), anyString());
    }

    @Test
    @DisplayName("Keycloak 계정 삭제가 실패해도 탈퇴는 유지 — 로그인은 AUTH_ACCOUNT_NOT_LINKED 로 막힌다")
    void withdraw_keycloakFails_keepsWithdrawal() {
        // given
        User user = member(7L, "kc-7");
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(user));
        willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR))
                .given(authService).deleteAccount("kc-7", "new1@gotgan.com");

        // when
        userService.withdraw(7L);

        // then
        assertThat(user.isDeleted()).isTrue();
    }
}
