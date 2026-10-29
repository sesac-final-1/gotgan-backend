package com.sesac.gojigi.store.store.service;

import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.dto.response.InternalStoreSummaryResponse;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import com.sesac.gojigi.store.store.repository.StoreRepository;
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

    /** 관리자 스토어 목록 — 페이징 없이 전체(컨벤션 4-7), status 가 null 이면 전체 */
    public List<InternalStoreSummaryResponse> getAdminList(StoreStatus status) {
        return storeRepository.findAllForAdmin(status).stream().map(InternalStoreSummaryResponse::from).toList();
    }

    /** 내부 상세 — 상태와 상관없이(CLOSED 포함) 응답, 없거나 소프트 삭제면 STORE_NOT_FOUND */
    public InternalStoreResponse getInternal(Long storeId) {
        return storeRepository.findByIdAndDeletedAtIsNull(storeId)
                .map(InternalStoreResponse::from)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
    }
}
