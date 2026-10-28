package com.sesac.gojigi.store.apply.service;

import com.sesac.gojigi.store.apply.dto.response.InternalApplicationSummaryResponse;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 입점 심사 조회 (담당 박지연, docs/api/store.md 3 · 4절) */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApplicationQueryService {

    private final StoreApplicationRepository storeApplicationRepository;

    /** 페이징 없이 전체 — 관리자 화면이 전체를 받아 탭 건수 · 검색 · 정렬을 한다 (컨벤션 4-7). status 가 null 이면 전체 */
    public List<InternalApplicationSummaryResponse> getList(ApplicationStatus status) {
        List<StoreApplication> applications = status == null
                ? storeApplicationRepository.findAllByOrderByTermsAgreedAtDescIdDesc()
                : storeApplicationRepository.findAllByStatusOrderByTermsAgreedAtDescIdDesc(status);
        return applications.stream().map(InternalApplicationSummaryResponse::from).toList();
    }
}
