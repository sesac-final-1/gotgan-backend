package com.sesac.gojigi.settlement.commission.service;

import com.sesac.gojigi.settlement.commission.dto.request.CommissionUpdateRequest;
import com.sesac.gojigi.settlement.commission.dto.response.CommissionResponse;
import com.sesac.gojigi.settlement.commission.entity.CategoryCommission;
import com.sesac.gojigi.settlement.commission.exception.CommissionErrorCode;
import com.sesac.gojigi.settlement.commission.exception.CommissionException;
import com.sesac.gojigi.settlement.commission.repository.CategoryCommissionRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 카테고리별 판매수수료 (STL-01) — admin-service 가 /internal/commissions 로 호출. 이력은 지우지 않고 적용 시작일로 쌓는다 */
@Service
@RequiredArgsConstructor
public class CommissionService {

    private final CategoryCommissionRepository categoryCommissionRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<CommissionResponse> list() {
        LocalDate today = LocalDate.now(clock);
        Map<Long, List<CategoryCommission>> byCategory = categoryCommissionRepository.findAll().stream()
                .collect(Collectors.groupingBy(CategoryCommission::getCategoryId, TreeMap::new, Collectors.toList()));
        return byCategory.entrySet().stream().map(e -> {
            List<CategoryCommission> rows = e.getValue().stream()
                    .sorted(Comparator.comparing(CategoryCommission::getEffectiveFrom)).toList();
            CommissionResponse.Rate current = rows.stream().filter(r -> !r.getEffectiveFrom().isAfter(today))
                    .reduce((a, b) -> b).map(CommissionService::rate).orElse(null);
            List<CommissionResponse.Rate> reserved = rows.stream().filter(r -> r.getEffectiveFrom().isAfter(today))
                    .map(CommissionService::rate).toList();
            return new CommissionResponse(e.getKey(), current, reserved);
        }).toList();
    }

    /** 여러 카테고리 한 번에 — 같은 (카테고리, 적용 시작일)이 있으면 율만 수정 */
    @Transactional
    public List<CommissionResponse> update(List<CommissionUpdateRequest> requests) {
        LocalDate today = LocalDate.now(clock);
        for (CommissionUpdateRequest r : requests) {
            LocalDate from = r.effectiveFrom() != null ? r.effectiveFrom()
                    : today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            if (from.isBefore(today)) {
                throw new CommissionException(CommissionErrorCode.COMMISSION_PAST_EFFECTIVE_DATE,
                        "적용 시작일은 오늘 이후여야 합니다 - categoryId=" + r.categoryId() + ", effectiveFrom=" + from);
            }
            categoryCommissionRepository.findByCategoryIdAndEffectiveFrom(r.categoryId(), from)
                    .ifPresentOrElse(existing -> existing.changeRate(r.rate()),
                            () -> categoryCommissionRepository.save(CategoryCommission.of(r.categoryId(), r.rate(), from)));
        }
        return list();
    }

    private static CommissionResponse.Rate rate(CategoryCommission c) {
        return new CommissionResponse.Rate(c.getCommissionRate(), c.getEffectiveFrom());
    }
}
