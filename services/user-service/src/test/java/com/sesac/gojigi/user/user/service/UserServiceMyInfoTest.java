package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.user.client.StoreClient;
import com.sesac.gojigi.user.user.dto.request.ChangePasswordRequest;
import com.sesac.gojigi.user.user.dto.request.UpdateMyInfoRequest;
import com.sesac.gojigi.user.user.dto.response.MyInfoResponse;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.Grade;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.repository.BlockedUserRedisRepository;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

/** 내 정보 조회 · 수정 · 비밀번호 변경 */
@ExtendWith(MockitoExtension.class)
class UserServiceMyInfoTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));

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
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
    }

    private static User member() {
        User user = User.signUp("buyer1@gotgan.com", "구매자1", "구매1", "010-0000-0004");
        ReflectionTestUtils.setField(user, "id", 4L);
        ReflectionTestUtils.setField(user, "pointBalance", 1500L);
        ReflectionTestUtils.setField(user, "totalConfirmedAmt", 82000L);
        ReflectionTestUtils.setField(user, "createdAt", LocalDateTime.of(2026, 10, 1, 9, 30));
        user.linkKeycloak("kc-4");
        return user;
    }

    @Test
    @DisplayName("내 정보 — userdb 값 그대로, 가입일은 Asia/Seoul 오프셋 포함")
    void getMyInfo_returnsProfile() {
        given(userRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.of(member()));

        MyInfoResponse me = userService.getMyInfo(4L);

        assertThat(me.userId()).isEqualTo(4L);
        assertThat(me.email()).isEqualTo("buyer1@gotgan.com");
        assertThat(me.name()).isEqualTo("구매자1");
        assertThat(me.nick()).isEqualTo("구매1");
        assertThat(me.phone()).isEqualTo("010-0000-0004");
        assertThat(me.grade()).isEqualTo(Grade.NORMAL);
        assertThat(me.signupProvider()).isEqualTo(SignupProvider.LOCAL);
        assertThat(me.point()).isEqualTo(1500L);
        assertThat(me.totalSpent()).isEqualTo(82000L);
        assertThat(me.joinedAt()).isEqualTo(OffsetDateTime.parse("2026-10-01T09:30:00+09:00"));
    }

    @Test
    @DisplayName("탈퇴 · 없는 회원은 USER_NOT_FOUND")
    void getMyInfo_notFound() {
        given(userRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getMyInfo(4L))
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("수정 — 보낸 항목만 바꾸고(이름 공백 제거 · 휴대폰 하이픈 정규화) 나머지는 그대로")
    void updateMyInfo_partial() {
        User user = member();
        given(userRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.of(user));

        MyInfoResponse me = userService.updateMyInfo(4L, new UpdateMyInfoRequest("  김곳간 ", null, "01012345678"));

        assertThat(me.name()).isEqualTo("김곳간");
        assertThat(me.nick()).isEqualTo("구매1");
        assertThat(me.phone()).isEqualTo("010-1234-5678");
        assertThat(user.getName()).isEqualTo("김곳간");
    }

    @Test
    @DisplayName("수정 — 닉네임 빈 문자열은 삭제, 10자리 휴대폰은 3-3-4")
    void updateMyInfo_clearNick_tenDigitPhone() {
        User user = member();
        given(userRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.of(user));

        userService.updateMyInfo(4L, new UpdateMyInfoRequest(null, " ", "0111234567"));

        assertThat(user.getNick()).isNull();
        assertThat(user.getPhone()).isEqualTo("011-123-4567");
        assertThat(user.getName()).isEqualTo("구매자1");
    }

    @Test
    @DisplayName("비밀번호 변경 — 현재 비밀번호 확인 후 Keycloak 에 새 비밀번호")
    void changePassword_verifiesThenResets() {
        given(userRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.of(member()));
        given(authService.verifyPassword("buyer1@gotgan.com", "Old!pass1")).willReturn(true);

        userService.changePassword(4L, new ChangePasswordRequest("Old!pass1", "New!pass2"));

        verify(authService).changePassword("kc-4", "buyer1@gotgan.com", "New!pass2");
    }

    @Test
    @DisplayName("현재 비밀번호 불일치 — 400 USER_PASSWORD_MISMATCH, 변경하지 않음")
    void changePassword_mismatch() {
        given(userRepository.findByIdAndDeletedAtIsNull(4L)).willReturn(Optional.of(member()));
        given(authService.verifyPassword("buyer1@gotgan.com", "wrong")).willReturn(false);

        assertThatThrownBy(() -> userService.changePassword(4L, new ChangePasswordRequest("wrong", "New!pass2")))
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_PASSWORD_MISMATCH);
        verify(authService, never()).changePassword(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("소셜 가입 회원 — Keycloak 비밀번호가 없어 409 USER_PASSWORD_CHANGE_UNSUPPORTED, Keycloak 을 부르지 않음")
    void changePassword_socialMember() {
        User social = User.fromSocialLogin("kc-8", "g@example.com", "구글회원", SignupProvider.GOOGLE);
        given(userRepository.findByIdAndDeletedAtIsNull(8L)).willReturn(Optional.of(social));

        assertThatThrownBy(() -> userService.changePassword(8L, new ChangePasswordRequest("x", "New!pass2")))
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_PASSWORD_CHANGE_UNSUPPORTED);
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("비밀번호 요청은 로그에 남지 않게 toString 을 가린다")
    void changePasswordRequest_masksToString() {
        assertThat(new ChangePasswordRequest("Old!pass1", "New!pass2").toString())
                .doesNotContain("Old!pass1").doesNotContain("New!pass2");
    }
}
