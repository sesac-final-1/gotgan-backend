package com.sesac.gojigi.store.apply.repository;

import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreApplicationRepository extends JpaRepository<StoreApplication, Long> {

    /** 관리자 심사 목록 — 제출 최신순, 같은 시각이면 나중에 들어온 신청 먼저 (store.md 3절) */
    List<StoreApplication> findAllByOrderByTermsAgreedAtDescIdDesc();

    List<StoreApplication> findAllByStatusOrderByTermsAgreedAtDescIdDesc(ApplicationStatus status);

    /** 진행 중 신청(심사대기 · 보완요청)이 있는지 — idx_store_application_user_id */
    boolean existsByUserIdAndStatusIn(Long userId, Collection<ApplicationStatus> statuses);

    /** 가장 최근 반려 — 30일 재신청 제한 기준 (STR-11) */
    Optional<StoreApplication> findFirstByUserIdAndStatusOrderByStatusChangedAtDesc(Long userId, ApplicationStatus status);

    boolean existsBySlugAndStatusIn(String slug, Collection<ApplicationStatus> statuses);

    /** 스토어명 중복 — 공백을 빼고 소문자로 비교 (normalizedName 도 같은 규칙으로 만든 값) */
    @Query("""
            select count(a) > 0 from StoreApplication a
            where a.status in :statuses and lower(replace(a.storeName, ' ', '')) = :normalizedName
            """)
    boolean existsByNormalizedStoreNameAndStatusIn(@Param("normalizedName") String normalizedName,
            @Param("statuses") Collection<ApplicationStatus> statuses);
}
