package com.sesac.gojigi.user.user.repository;

import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 회원 — 활성 조회는 deleted_at IS NULL (컨벤션 3-5).
 * point_balance · total_confirmed_amt 는 동시 변경 경합이 있어 조건부 UPDATE 로만 바꾼다 (컨벤션 2-6 동시성).
 * UPDATE 는 영속성 컨텍스트를 거치지 않으므로 clearAutomatically — 같은 트랜잭션에서 다시 읽으면 DB 값을 본다.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    Optional<User> findByKeycloakId(String keycloakId);

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    /** 가입 중복 검사 — uk_users_email 은 탈퇴 회원도 포함하므로 deleted_at 과 관계없이 본다 */
    boolean existsByEmail(String email);

    /** 적립 회수(RECLAIM)처럼 현재 잔액을 읽고 계산해야 할 때만 — 행 잠금 (SELECT ... FOR UPDATE) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId and u.deletedAt is null")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    /** 포인트 사용 — 잔액이 모자라면 0건 (음수 금지 · 동시 차감 경합 방지). 반환값 0 이면 POINT_INSUFFICIENT */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.pointBalance = u.pointBalance - :amount "
            + "where u.id = :userId and u.deletedAt is null and u.pointBalance >= :amount")
    int deductPoint(@Param("userId") Long userId, @Param("amount") long amount);

    /** 포인트 적립 · 사용 환원 — 반환값 0 이면 회원 없음 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.pointBalance = u.pointBalance + :amount where u.id = :userId and u.deletedAt is null")
    int addPoint(@Param("userId") Long userId, @Param("amount") long amount);

    /** 구매확정 금액 누적 (OrderItemConfirmed) — 등급 재계산은 기준이 정해지면 서비스에서 함께 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.totalConfirmedAmt = u.totalConfirmedAmt + :amount where u.id = :userId")
    int addConfirmedAmount(@Param("userId") Long userId, @Param("amount") long amount);

    /** 관리자 대시보드 — 상태별 회원 수 (GET /internal/stats/users) */
    long countByStatusAndDeletedAtIsNull(UserStatus status);

    /** 관리자 대시보드 — 기간 내 신규 가입 수 [from, to) */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThanAndDeletedAtIsNull(LocalDateTime from, LocalDateTime to);
}
