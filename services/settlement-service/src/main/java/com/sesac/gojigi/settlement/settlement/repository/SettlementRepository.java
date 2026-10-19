package com.sesac.gojigi.settlement.settlement.repository;

import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByStoreIdAndWeekStart(Long storeId, LocalDate weekStart);

    List<Settlement> findByWeekStartOrderByStoreIdAsc(LocalDate weekStart);

    List<Settlement> findByStoreIdOrderByWeekStartDesc(Long storeId);

    List<Settlement> findByStoreIdAndStatus(Long storeId, SettlementStatus status);

    List<Settlement> findByStatusIn(Collection<SettlementStatus> statuses);

    List<Settlement> findByStatusAndPaidAtGreaterThanEqual(SettlementStatus status, LocalDateTime from);
}
