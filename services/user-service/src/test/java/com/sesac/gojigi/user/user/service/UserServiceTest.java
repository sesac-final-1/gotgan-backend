package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.common.exception.CommonErrorCode;
import com.sesac.gojigi.user.user.client.StoreClient;
import com.sesac.gojigi.user.user.client.StoreStatus;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.BlockedUserRedisRepository;
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
    private StoreClient storeClient;

    @Mock
    private BlockedUserRedisRepository blockedUserRedisRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, authService, storeClient, blockedUserRedisRepository, transactionTemplate, CLOCK);
        // 트랜잭션 템플릿은 콜백을 그대로 실행
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
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
        userService.withdraw(7L, null);

        // then
        assertThat(user.getDeletedAt()).isEqualTo(LocalDateTime.now(CLOCK));
        assertThat(user.getEmail()).isEqualTo("new1@gotgan.com");
        verify(authService).deleteAccount("kc-7", "new1@gotgan.com");
        verify(blockedUserRedisRepository).block(7L, BlockedUserRedisRepository.WITHDRAWN);   // 남은 Access Token 차단
    }

    @Test
    @DisplayName("없는 회원 · 이미 탈퇴한 회원은 USER_NOT_FOUND — Keycloak 은 건드리지 않는다")
    void withdraw_notFound_throws() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.empty());

        // when · then
        assertThatThrownBy(() -> userService.withdraw(7L, null))
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
        userService.withdraw(7L, null);

        // then
        assertThat(user.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("구매자(X-Store-Id 없음)는 스토어를 확인하지 않는다")
    void withdraw_buyer_skipsStoreCheck() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(member(7L, "kc-7")));

        // when
        userService.withdraw(7L, null);

        // then
        verifyNoInteractions(storeClient);
    }

    @Test
    @DisplayName("판매자 — 스토어가 폐점(CLOSED)이 아니면 USER_SELLER_STORE_NOT_CLOSED, 회원 · 계정은 그대로")
    void withdraw_sellerWithOpenStore_throws() {
        // given
        given(storeClient.getStoreStatus(1L)).willReturn(StoreStatus.ACTIVE);

        // when · then
        assertThatThrownBy(() -> userService.withdraw(2L, 1L))
                .isInstanceOf(UserException.class)
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_SELLER_STORE_NOT_CLOSED);
        verify(userRepository, never()).findByIdAndDeletedAtIsNull(any());
        verify(authService, never()).deleteAccount(any(), anyString());
    }

    @Test
    @DisplayName("판매자 — 휴면 · 정지 스토어도 폐점이 아니라 탈퇴 불가")
    void withdraw_sellerWithDormantOrSuspendedStore_throws() {
        // given
        given(storeClient.getStoreStatus(1L)).willReturn(StoreStatus.DORMANT);
        given(storeClient.getStoreStatus(2L)).willReturn(StoreStatus.SUSPENDED);

        // when · then
        assertThatThrownBy(() -> userService.withdraw(2L, 1L)).isInstanceOf(UserException.class);
        assertThatThrownBy(() -> userService.withdraw(3L, 2L)).isInstanceOf(UserException.class);
    }

    @Test
    @DisplayName("판매자 — 스토어가 폐점(CLOSED)이면 탈퇴된다")
    void withdraw_sellerWithClosedStore_withdraws() {
        // given
        User seller = member(2L, "kc-2");
        given(storeClient.getStoreStatus(1L)).willReturn(StoreStatus.CLOSED);
        given(userRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(seller));

        // when
        userService.withdraw(2L, 1L);

        // then
        assertThat(seller.isDeleted()).isTrue();
        verify(authService).deleteAccount("kc-2", "new1@gotgan.com");
    }

    @Test
    @DisplayName("차단 목록 저장(Redis)이 실패해도 탈퇴는 유지 — gateway 차단만 빠지고 user-service 는 deleted_at 으로 막는다")
    void withdraw_blocklistFails_keepsWithdrawal() {
        // given
        User user = member(7L, "kc-7");
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(user));
        willThrow(new IllegalStateException("redis down")).given(blockedUserRedisRepository)
                .block(7L, BlockedUserRedisRepository.WITHDRAWN);

        // when
        userService.withdraw(7L, null);

        // then
        assertThat(user.isDeleted()).isTrue();
        verify(authService).deleteAccount("kc-7", "new1@gotgan.com");
    }

    @Test
    @DisplayName("Keycloak 삭제가 실패해도 차단 목록은 저장한다 — 남은 토큰은 여전히 막아야 한다")
    void withdraw_keycloakFails_stillBlocksTokens() {
        // given
        User user = member(7L, "kc-7");
        given(userRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(user));
        willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR))
                .given(authService).deleteAccount("kc-7", "new1@gotgan.com");

        // when
        userService.withdraw(7L, null);

        // then
        verify(blockedUserRedisRepository).block(7L, BlockedUserRedisRepository.WITHDRAWN);
    }
}
