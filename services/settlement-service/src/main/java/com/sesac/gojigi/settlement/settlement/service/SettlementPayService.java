package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.settlement.settlement.dto.response.PayResultResponse;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 지급 실행 (단건 · 일괄, STL-06) — 정산서마다 따로 커밋해서 일부가 실패해도 나머지는 지급된다.
 * 응답 {success[], failed[{id, code}]} — 같은 ids 를 다시 보내면 이미 지급된 건은 SETTLE_INVALID_STATUS 로 실패 목록에 들어간다(이중 지급 없음).
 */
@Service
@RequiredArgsConstructor
public class SettlementPayService {

    private final SettlementCommandService settlementCommandService;

    public PayResultResponse pay(List<Long> settlementIds) {
        List<Long> success = new ArrayList<>();
        List<PayResultResponse.Failure> failed = new ArrayList<>();
        for (Long id : settlementIds.stream().distinct().toList()) {
            try {
                settlementCommandService.payOne(id);
                success.add(id);
            } catch (BusinessException e) {
                failed.add(new PayResultResponse.Failure(id, e.getErrorCode().getCode(), e.getMessage()));
            } catch (ObjectOptimisticLockingFailureException e) {
                failed.add(new PayResultResponse.Failure(id, "SETTLE_INVALID_STATUS", "다른 요청이 먼저 처리했습니다."));
            }
        }
        return new PayResultResponse(success, failed);
    }
}
