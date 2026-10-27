package com.sesac.gojigi.order.claim.service;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.claim.dto.request.ClaimStage;
import com.sesac.gojigi.order.claim.dto.response.ClaimDetailResponse;
import com.sesac.gojigi.order.claim.dto.response.SellerClaimListResponse;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 클레임 조회 — 내 클레임 내역(구매자) · 판매자 클레임 목록 · 상세 (CLM-05) */
@Service
@RequiredArgsConstructor
public class ClaimQueryService {

    private final OrderClaimRepository claimRepository;

    /** 내 클레임 전체 — 최신 요청 먼저 */
    @Transactional(readOnly = true)
    public List<ClaimDetailResponse> listMine(Long buyerId) {
        return claimRepository.findMine(buyerId).stream().map(ClaimDetailResponse::from).toList();
    }

    /** 판매자 클레임 목록 — 단계(처리대기 · 처리완료) · 유형(없으면 전체) · 서버 페이징 + 탭 숫자 */
    @Transactional(readOnly = true)
    public SellerClaimListResponse listSeller(Long storeId, ClaimStage stage, ClaimType type, int page, int size) {
        requireStore(storeId);
        PageResponse<ClaimDetailResponse> items = PageResponse.from(claimRepository
                .searchSeller(storeId, stage.statuses(), type, PageRequest.of(page, size)).map(ClaimDetailResponse::from));
        Map<ClaimStage, Long> stageCounts = new EnumMap<>(ClaimStage.class);
        Map<ClaimType, Long> typeCounts = new EnumMap<>(ClaimType.class);
        for (ClaimStage s : ClaimStage.values()) {
            stageCounts.put(s, 0L);
        }
        for (ClaimType t : ClaimType.values()) {
            typeCounts.put(t, 0L);
        }
        for (Object[] r : claimRepository.countByTypeAndStatus(storeId)) {
            ClaimType t = (ClaimType) r[0];
            ClaimStage s = ClaimStage.of((ClaimStatus) r[1]);
            long n = ((Number) r[2]).longValue();
            if (type == null || type == t) {
                stageCounts.merge(s, n, Long::sum);
            }
            if (s == stage) {
                typeCounts.merge(t, n, Long::sum);
            }
        }
        return new SellerClaimListResponse(stageCounts, typeCounts, items);
    }

    /** 판매자 클레임 상세 — 없거나 남의 스토어면 404 */
    @Transactional(readOnly = true)
    public ClaimDetailResponse getSeller(Long storeId, Long claimId) {
        requireStore(storeId);
        return claimRepository.findById(claimId).filter(c -> c.getOrderItem().getStoreId().equals(storeId))
                .map(ClaimDetailResponse::from)
                .orElseThrow(() -> new ClaimException(ClaimErrorCode.CLAIM_NOT_FOUND));
    }

    private static void requireStore(Long storeId) {
        if (storeId == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "스토어 정보가 없습니다.");
        }
    }
}
