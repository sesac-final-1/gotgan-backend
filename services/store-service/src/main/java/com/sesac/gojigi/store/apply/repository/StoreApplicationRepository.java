package com.sesac.gojigi.store.apply.repository;

import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreApplicationRepository extends JpaRepository<StoreApplication, Long> {

    /** 관리자 심사 목록 — 제출 최신순, 같은 시각이면 나중에 들어온 신청 먼저 (store.md 3절) */
    List<StoreApplication> findAllByOrderByTermsAgreedAtDescIdDesc();

    List<StoreApplication> findAllByStatusOrderByTermsAgreedAtDescIdDesc(ApplicationStatus status);

    /** 신청자의 가장 최근 신청 — 내 신청 상태(2-2절). 진행 중은 1건뿐이고, 반려 뒤 재신청하면 새 신청이 최신 */
    Optional<StoreApplication> findFirstByUserIdOrderByTermsAgreedAtDescIdDesc(Long userId);

    /** 같은 사업자번호의 다른 신청 — 중복 입점 확인 (idx_store_application_biz_reg_no, store.md 4절) */
    List<StoreApplication> findAllByBizRegNoAndIdNotOrderByTermsAgreedAtDescIdDesc(String bizRegNo, Long id);

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

    /** 자동 반려 대상 — 보완요청 시각(status_changed_at)이 before 이전(포함)인 보완요청 중 신청, 오래된 순 (7-1절) */
    @Query("""
            select a.id from StoreApplication a
            where a.status = com.sesac.gojigi.store.apply.entity.ApplicationStatus.SUPPLEMENT and a.statusChangedAt <= :before
            order by a.statusChangedAt, a.id
            """)
    List<Long> findExpiredSupplementIds(@Param("before") LocalDateTime before, Limit limit);

    /** 재제출로 주소를 바꿀 때 — 자기 신청은 빼고 진행 중 신청과 비교 */
    boolean existsBySlugAndStatusInAndIdNot(String slug, Collection<ApplicationStatus> statuses, Long id);

    /** 재제출로 스토어명을 바꿀 때 — 자기 신청은 빼고 (규칙은 existsByNormalizedStoreNameAndStatusIn 과 같음) */
    @Query("""
            select count(a) > 0 from StoreApplication a
            where a.status in :statuses and a.id <> :id and lower(replace(a.storeName, ' ', '')) = :normalizedName
            """)
    boolean existsByNormalizedStoreNameAndStatusInAndIdNot(@Param("normalizedName") String normalizedName,
            @Param("statuses") Collection<ApplicationStatus> statuses, @Param("id") Long id);
}
