package com.sesac.gojigi.product.store.repository;

import com.sesac.gojigi.product.store.entity.StoreStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreStatusRepository extends JpaRepository<StoreStatus, Long> {

    /** 같은 스토어 이벤트가 동시에 와도 차례로 — 시각 비교 · 상품 반영을 한 덩어리로 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StoreStatus s where s.storeId = :storeId")
    Optional<StoreStatus> findForUpdate(@Param("storeId") Long storeId);
}
