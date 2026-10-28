package com.sesac.gojigi.store.store.repository;

import com.sesac.gojigi.store.store.entity.Store;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreRepository extends JpaRepository<Store, Long> {

    /** 활성 조회 — 소프트 삭제(deleted_at)된 스토어는 없는 것으로 본다 (컨벤션 3-5) */
    Optional<Store> findByIdAndDeletedAtIsNull(Long id);

    /** 승인된 신청으로 만든 스토어 — uk_store_store_application_id */
    Optional<Store> findByStoreApplicationId(Long storeApplicationId);

    /** 같은 사업자번호의 다른 신청으로 만든 스토어 — 폐점 포함, 소프트 삭제 제외 (store.md 4절 sameBizRegNo) */
    List<Store> findAllByStoreApplicationIdInAndDeletedAtIsNullOrderByIdAsc(Collection<Long> storeApplicationIds);

    // 입점 신청 검사 — uk_store_user_id · uk_store_slug · uk_store_store_name 이 모든 행(폐점 · 소프트 삭제 포함)에 걸리므로 조건 없이 본다

    boolean existsByUserId(Long userId);

    boolean existsBySlug(String slug);

    /** 스토어명 중복 — 공백을 빼고 소문자로 비교 (normalizedName 도 같은 규칙으로 만든 값) */
    @Query("select count(s) > 0 from Store s where lower(replace(s.storeName, ' ', '')) = :normalizedName")
    boolean existsByNormalizedStoreName(@Param("normalizedName") String normalizedName);
}
