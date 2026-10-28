package com.sesac.gojigi.store.store.repository;

import com.sesac.gojigi.store.store.entity.StoreStatusHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreStatusHistoryRepository extends JpaRepository<StoreStatusHistory, Long> {

    /** 오래된 순 — INSERT 전용이라 ID 순서 = 기록 순서 */
    List<StoreStatusHistory> findAllByStoreIdOrderByIdAsc(Long storeId);
}
