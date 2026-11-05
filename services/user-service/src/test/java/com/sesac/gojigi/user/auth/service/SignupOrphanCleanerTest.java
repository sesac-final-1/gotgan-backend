package com.sesac.gojigi.user.auth.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import com.sesac.gojigi.user.auth.client.dto.KeycloakAccount;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignupOrphanCleanerTest {

    private static final String EMAIL = "new1@gotgan.com";

    @Mock
    private KeycloakClientAdapter adapter;

    private SignupOrphanCleaner cleaner(Duration delay, int maxPending) {
        return new SignupOrphanCleaner(adapter, delay, maxPending);
    }

    @Test
    @DisplayName("user_id 가 실패한 가입과 같은 계정을 지운다")
    void schedule_deletesSameUsersAccount() {
        given(adapter.findAccount(EMAIL)).willReturn(Optional.of(new KeycloakAccount("kc-8", 8L)));

        cleaner(Duration.ZERO, 10).schedule(EMAIL, 8L);

        verify(adapter, timeout(2000)).deleteUser("kc-8", null);
    }

    @Test
    @DisplayName("그 사이 다시 가입한 계정(user_id 다름) · 없는 계정은 지우지 않는다")
    void schedule_keepsOtherAccounts() {
        given(adapter.findAccount(EMAIL)).willReturn(Optional.of(new KeycloakAccount("kc-9", 9L))).willReturn(Optional.empty());
        SignupOrphanCleaner cleaner = cleaner(Duration.ZERO, 10);

        cleaner.schedule(EMAIL, 8L);
        cleaner.schedule(EMAIL, 8L);

        verify(adapter, timeout(2000).times(2)).findAccount(EMAIL);
        verify(adapter, after(100).never()).deleteUser(any(), any());
    }

    @Test
    @DisplayName("정리 실패는 다음 작업을 막지 않는다 — 재가입 때 정리된다")
    void schedule_failureDoesNotStopNext() {
        willThrow(new IllegalStateException("down")).given(adapter).deleteUser("kc-8", null);
        given(adapter.findAccount(EMAIL)).willReturn(Optional.of(new KeycloakAccount("kc-8", 8L)))
                .willReturn(Optional.of(new KeycloakAccount("kc-9", 9L)));
        SignupOrphanCleaner cleaner = cleaner(Duration.ZERO, 10);

        cleaner.schedule(EMAIL, 8L);
        cleaner.schedule(EMAIL, 9L);

        verify(adapter, timeout(2000)).deleteUser("kc-9", null);
    }

    @Test
    @DisplayName("대기가 차면 더 받지 않는다 — 과부하 때 Keycloak 부하를 키우지 않는다")
    void schedule_overCapacity_drops() {
        cleaner(Duration.ZERO, 0).schedule(EMAIL, 8L);

        verify(adapter, after(100).never()).findAccount(EMAIL);
    }
}
