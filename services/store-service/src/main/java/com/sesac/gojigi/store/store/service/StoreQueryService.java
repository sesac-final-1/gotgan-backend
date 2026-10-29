package com.sesac.gojigi.store.store.service;

import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreSummaryResponse;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import com.sesac.gojigi.store.store.repository.StoreStatusHistoryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 스토어 조회 (담당 박지연) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreQueryService {

    private final StoreRepository storeRepository;
    private final StoreApplicationRepository storeApplicationRepository;
    private final StoreStatusHistoryRepository storeStatusHistoryRepository;

    /** 관리자 스토어 목록 — 페이징 없이 전체(컨벤션 4-7), status 가 null 이면 전체 */
    public List<InternalStoreSummaryResponse> getAdminList(StoreStatus status) {
        return storeRepository.findAllForAdmin(status).stream().map(InternalStoreSummaryResponse::from).toList();
    }

    /**
     * 내부 상세 — 상태와 상관없이(CLOSED 포함) 응답, 없거나 소프트 삭제면 STORE_NOT_FOUND.
     * 대표자 · 상호 · 사업자번호는 원래 신청서에서(store 에 없음, ERD 결정 4-4), 상태 이력은 오래된 순 — 조회 3번(store.md 10/26 설계 정리 8)
     */
    public InternalStoreResponse getInternal(Long storeId) {
        Store store = storeRepository.findByIdAndDeletedAtIsNull(storeId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
        StoreApplication application = storeApplicationRepository.findById(store.getStoreApplicationId()).orElseThrow(); // FK — 신청서는 지우지 않는다
        return InternalStoreResponse.of(store, application, storeStatusHistoryRepository.findAllByStoreIdOrderByIdAsc(storeId));
    }
}
