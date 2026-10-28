package com.sesac.gojigi.store.apply.repository;

import com.sesac.gojigi.store.apply.entity.ApplicationHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationHistoryRepository extends JpaRepository<ApplicationHistory, Long> {

    /** 오래된 순 — INSERT 전용이라 ID 순서 = 기록 순서 */
    List<ApplicationHistory> findAllByStoreApplicationIdOrderByIdAsc(Long storeApplicationId);
}
