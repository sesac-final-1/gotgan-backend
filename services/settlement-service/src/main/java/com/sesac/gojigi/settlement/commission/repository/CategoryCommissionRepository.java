package com.sesac.gojigi.settlement.commission.repository;

import com.sesac.gojigi.settlement.commission.entity.CategoryCommission;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryCommissionRepository extends JpaRepository<CategoryCommission, Long> {

    /** 그 날짜에 유효한 수수료율 — 적용 시작일이 그날 이전인 행 중 가장 늦은 것 (예약분은 제외) */
    Optional<CategoryCommission> findFirstByCategoryIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            Long categoryId, LocalDate date);

    Optional<CategoryCommission> findByCategoryIdAndEffectiveFrom(Long categoryId, LocalDate effectiveFrom);
}
