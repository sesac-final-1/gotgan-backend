package com.sesac.gojigi.user.user.entity;

import com.sesac.gojigi.user.common.entity.BaseTimeEntity;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 프로필 · 포인트 잔액 · 등급 — 테이블 users (MySQL 키워드 user 회피, 컨벤션 3-1 예외).
 * 계정(이메일 · 비밀번호 · 역할)의 주인은 Keycloak 이고, 이 엔티티는 keycloak_id 로 연결만 한다.
 * point_balance · total_confirmed_amt 는 동시 변경 경합이 있어 엔티티 메서드가 아니라 조건부 UPDATE(UserRepository)로만 바꾼다.
 */
@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "keycloak_id", unique = true, length = 36)
    private String keycloakId;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "nick", length = 30)
    private String nick;

    @Column(name = "phone", length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "signup_provider", nullable = false, length = 20)
    private SignupProvider signupProvider;

    @Column(name = "point_balance", nullable = false)
    private long pointBalance;

    @Column(name = "total_confirmed_amt", nullable = false)
    private long totalConfirmedAmt;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", nullable = false, length = 20)
    private Grade grade;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "suspended_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime suspendedAt;

    @Column(name = "last_login_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime lastLoginAt;

    @Column(name = "deleted_at", columnDefinition = "DATETIME(6)")
    private LocalDateTime deletedAt;

    @Builder
    private User(String keycloakId, String email, String name, String nick, String phone, SignupProvider signupProvider) {
        this.keycloakId = keycloakId;
        this.email = email;
        this.name = name;
        this.nick = nick;
        this.phone = phone;
        this.signupProvider = signupProvider == null ? SignupProvider.LOCAL : signupProvider;
        this.pointBalance = 0L;
        this.totalConfirmedAmt = 0L;
        this.grade = Grade.NORMAL;
        this.status = UserStatus.ACTIVE;
    }

    /** 자체 가입 — keycloak_id 는 Keycloak 계정 생성 직후 linkKeycloak 으로 채운다 (실패 시 서비스가 행 삭제) */
    public static User signUp(String email, String name, String nick, String phone) {
        return User.builder()
                .email(email)
                .name(name)
                .nick(nick)
                .phone(phone)
                .signupProvider(SignupProvider.LOCAL)
                .build();
    }

    /** 소셜 첫 로그인 — Keycloak First Broker Login 을 마친 계정으로 행을 만든다 (phone 은 주문 전 입력) */
    public static User fromSocialLogin(String keycloakId, String email, String name, SignupProvider provider) {
        return User.builder()
                .keycloakId(keycloakId)
                .email(email)
                .name(name)
                .signupProvider(provider)
                .build();
    }

    public void linkKeycloak(String keycloakId) {
        this.keycloakId = keycloakId;
    }

    public void updateProfile(String name, String nick, String phone) {
        this.name = name;
        this.nick = nick;
        this.phone = phone;
    }

    public void recordLogin(LocalDateTime loginAt) {
        this.lastLoginAt = loginAt;
    }

    /** 관리자 이용정지 — 사유 필수. Keycloak enabled=false 동기화는 서비스 계층 */
    public void suspend(String reason, LocalDateTime suspendedAt) {
        requireReason(reason);
        if (status != UserStatus.ACTIVE) {
            throw new UserException(UserErrorCode.USER_INVALID_STATUS_TRANSITION);
        }
        this.status = UserStatus.SUSPENDED;
        this.statusReason = reason;
        this.suspendedAt = suspendedAt;
    }

    /** 관리자 정지 해제 — 사유 필수 */
    public void activate(String reason) {
        requireReason(reason);
        if (status != UserStatus.SUSPENDED) {
            throw new UserException(UserErrorCode.USER_INVALID_STATUS_TRANSITION);
        }
        this.status = UserStatus.ACTIVE;
        this.statusReason = reason;
        this.suspendedAt = null;
    }

    /** 회원 물리 DELETE 금지 — deleted_at 기록 (컨벤션 3-5) */
    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE && !isDeleted();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new UserException(UserErrorCode.USER_STATUS_REASON_REQUIRED);
        }
    }
}
