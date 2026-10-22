package com.sesac.gojigi.user.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.user.user.entity.status.Grade;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UserTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 0);

    private static User activeUser() {
        return User.signUp("user@gotgan.com", "홍길동", "길동", "010-1234-5678");
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("자체 가입은 LOCAL · 포인트 0 · NORMAL · ACTIVE 로 시작하고 keycloak_id 는 비어 있다")
        void signUp_startsWithDefaults() {
            // when
            User user = activeUser();

            // then
            assertThat(user.getSignupProvider()).isEqualTo(SignupProvider.LOCAL);
            assertThat(user.getPointBalance()).isZero();
            assertThat(user.getTotalConfirmedAmt()).isZero();
            assertThat(user.getGrade()).isEqualTo(Grade.NORMAL);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getKeycloakId()).isNull();
            assertThat(user.isActive()).isTrue();
        }

        @Test
        @DisplayName("소셜 첫 로그인은 keycloak_id 와 가입 경로를 갖고 시작한다")
        void fromSocialLogin_setsKeycloakIdAndProvider() {
            // when
            User user = User.fromSocialLogin("kc-uuid", "kakao@gotgan.com", "카카오", SignupProvider.KAKAO);

            // then
            assertThat(user.getKeycloakId()).isEqualTo("kc-uuid");
            assertThat(user.getSignupProvider()).isEqualTo(SignupProvider.KAKAO);
            assertThat(user.getPhone()).isNull();
        }

        @Test
        @DisplayName("가입 경로를 주지 않으면 LOCAL 이다")
        void builder_withoutProvider_defaultsToLocal() {
            // when
            User user = User.builder().email("a@gotgan.com").name("홍길동").build();

            // then
            assertThat(user.getSignupProvider()).isEqualTo(SignupProvider.LOCAL);
        }
    }

    @Nested
    @DisplayName("연결 · 프로필")
    class Profile {

        @Test
        @DisplayName("Keycloak 계정 생성 후 keycloak_id 를 연결한다")
        void linkKeycloak_setsKeycloakId() {
            // given
            User user = activeUser();

            // when
            user.linkKeycloak("kc-uuid");

            // then
            assertThat(user.getKeycloakId()).isEqualTo("kc-uuid");
        }

        @Test
        @DisplayName("이름 · 닉네임 · 휴대폰을 수정하고 이메일은 바꾸지 않는다")
        void updateProfile_changesProfileButNotEmail() {
            // given
            User user = activeUser();

            // when
            user.updateProfile("김철수", "철수", "010-9999-0000");

            // then
            assertThat(user.getName()).isEqualTo("김철수");
            assertThat(user.getNick()).isEqualTo("철수");
            assertThat(user.getPhone()).isEqualTo("010-9999-0000");
            assertThat(user.getEmail()).isEqualTo("user@gotgan.com");
        }

        @Test
        @DisplayName("마지막 로그인 시각을 기록한다")
        void recordLogin_setsLastLoginAt() {
            // given
            User user = activeUser();

            // when
            user.recordLogin(NOW);

            // then
            assertThat(user.getLastLoginAt()).isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("이용정지 · 해제")
    class Suspension {

        @Test
        @DisplayName("활성 회원을 사유와 함께 정지한다")
        void suspend_whenActive_suspends() {
            // given
            User user = activeUser();

            // when
            user.suspend("부정 거래", NOW, null);

            // then
            assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
            assertThat(user.getStatusReason()).isEqualTo("부정 거래");
            assertThat(user.getSuspendedAt()).isEqualTo(NOW);
            assertThat(user.isActive()).isFalse();
        }

        @Test
        @DisplayName("이미 정지된 회원은 다시 정지할 수 없다")
        void suspend_whenAlreadySuspended_throws() {
            // given
            User user = activeUser();
            user.suspend("부정 거래", NOW, null);

            // when · then
            assertThatThrownBy(() -> user.suspend("재정지", NOW, null))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e).getErrorCode())
                    .isEqualTo(UserErrorCode.USER_INVALID_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("정지 사유가 비어 있으면 정지할 수 없다")
        void suspend_whenReasonBlank_throws() {
            // given
            User user = activeUser();

            // when · then
            assertThatThrownBy(() -> user.suspend("  ", NOW, null))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e).getErrorCode())
                    .isEqualTo(UserErrorCode.USER_STATUS_REASON_REQUIRED);
            assertThatThrownBy(() -> user.suspend(null, NOW, null))
                    .isInstanceOf(UserException.class);
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("정지된 회원을 사유와 함께 해제하면 정지 시각 · 종료 시각이 지워진다")
        void activate_whenSuspended_activates() {
            // given
            User user = activeUser();
            user.suspend("부정 거래", NOW, NOW.plusDays(7));

            // when
            user.activate("소명 완료");

            // then
            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getStatusReason()).isEqualTo("소명 완료");
            assertThat(user.getSuspendedAt()).isNull();
            assertThat(user.getSuspendedUntil()).isNull();
            assertThat(user.isActive()).isTrue();
        }

        @Test
        @DisplayName("기간 정지는 종료 시각부터 만료, 영구 정지(종료 시각 없음) · 정상 회원은 만료되지 않는다")
        void isSuspensionExpired() {
            // given
            User sevenDays = activeUser();
            sevenDays.suspend("부정 거래", NOW, NOW.plusDays(7));
            User permanent = activeUser();
            permanent.suspend("부정 거래", NOW, null);

            // when · then
            assertThat(sevenDays.getSuspendedUntil()).isEqualTo(NOW.plusDays(7));
            assertThat(sevenDays.isSuspensionExpired(NOW.plusDays(7).minusNanos(1000))).isFalse();
            assertThat(sevenDays.isSuspensionExpired(NOW.plusDays(7))).isTrue();
            assertThat(permanent.isSuspensionExpired(NOW.plusYears(10))).isFalse();
            assertThat(activeUser().isSuspensionExpired(NOW)).isFalse();
        }

        @Test
        @DisplayName("정지되지 않은 회원은 해제할 수 없다")
        void activate_whenActive_throws() {
            // given
            User user = activeUser();

            // when · then
            assertThatThrownBy(() -> user.activate("해제"))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e).getErrorCode())
                    .isEqualTo(UserErrorCode.USER_INVALID_STATUS_TRANSITION);
        }

        @Test
        @DisplayName("해제 사유가 비어 있으면 해제할 수 없다")
        void activate_whenReasonBlank_throws() {
            // given
            User user = activeUser();
            user.suspend("부정 거래", NOW, null);

            // when · then
            assertThatThrownBy(() -> user.activate(""))
                    .isInstanceOf(UserException.class)
                    .extracting(e -> ((UserException) e).getErrorCode())
                    .isEqualTo(UserErrorCode.USER_STATUS_REASON_REQUIRED);
        }
    }

    @Nested
    @DisplayName("탈퇴")
    class Deletion {

        @Test
        @DisplayName("탈퇴하면 deleted_at 이 기록되고 활성 회원이 아니다")
        void softDelete_marksDeleted() {
            // given
            User user = activeUser();

            // when
            user.softDelete(NOW);

            // then
            assertThat(user.isDeleted()).isTrue();
            assertThat(user.getDeletedAt()).isEqualTo(NOW);
            assertThat(user.isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("에러 코드 문자열은 enum 상수명과 같다")
    void userErrorCode_codeEqualsName() {
        for (UserErrorCode code : UserErrorCode.values()) {
            assertThat(code.getCode()).isEqualTo(code.name());
            assertThat(code.getMessage()).isNotBlank();
        }
        assertThat(UserErrorCode.USER_NOT_FOUND.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
