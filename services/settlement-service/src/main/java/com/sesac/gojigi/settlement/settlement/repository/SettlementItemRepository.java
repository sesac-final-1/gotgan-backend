package com.sesac.gojigi.settlement.settlement.repository;

import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementItemRepository extends JpaRepository<SettlementItem, Long> {

    boolean existsByOrderItemId(Long orderItemId);

    Optional<SettlementItem> findByOrderItemId(Long orderItemId);
}
