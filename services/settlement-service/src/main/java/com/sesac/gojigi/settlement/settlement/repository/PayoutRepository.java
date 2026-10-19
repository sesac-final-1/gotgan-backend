package com.sesac.gojigi.settlement.settlement.repository;

import com.sesac.gojigi.settlement.settlement.entity.Payout;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    Optional<Payout> findBySettlementId(Long settlementId);
}
