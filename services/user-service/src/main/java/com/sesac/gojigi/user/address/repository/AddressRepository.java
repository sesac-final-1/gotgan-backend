package com.sesac.gojigi.user.address.repository;

import com.sesac.gojigi.user.address.entity.Address;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 배송지 — 활성 조회는 deleted_at IS NULL. 소유권 검사는 Address#isOwnedBy 또는 user_id 조건으로 */
public interface AddressRepository extends JpaRepository<Address, Long> {

    /** 내 배송지 목록 — 기본 배송지 먼저, 그다음 최근 등록순 (idx_address_user_default) */
    @Query("select a from Address a where a.user.id = :userId and a.deletedAt is null "
            + "order by a.isDefault desc, a.id desc")
    List<Address> findActiveByUserId(@Param("userId") Long userId);

    /** 소유권까지 한 번에 — 다른 회원의 배송지는 조회되지 않는다 */
    @Query("select a from Address a where a.id = :addressId and a.user.id = :userId and a.deletedAt is null")
    Optional<Address> findActiveByIdAndUserId(@Param("addressId") Long addressId, @Param("userId") Long userId);

    @Query("select a from Address a where a.user.id = :userId and a.isDefault = true and a.deletedAt is null")
    Optional<Address> findDefaultByUserId(@Param("userId") Long userId);

    @Query("select count(a) from Address a where a.user.id = :userId and a.deletedAt is null")
    long countActiveByUserId(@Param("userId") Long userId);

    /** 새 기본 배송지 지정 전에 기존 기본 해제 — 같은 트랜잭션에서 (회원당 기본 1개는 서비스가 보장) */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Address a set a.isDefault = false where a.user.id = :userId and a.isDefault = true")
    int clearDefault(@Param("userId") Long userId);
}
