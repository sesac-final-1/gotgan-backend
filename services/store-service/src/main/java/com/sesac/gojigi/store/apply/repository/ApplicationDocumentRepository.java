package com.sesac.gojigi.store.apply.repository;

import com.sesac.gojigi.store.apply.entity.ApplicationDocument;
import com.sesac.gojigi.store.apply.entity.DocType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationDocumentRepository extends JpaRepository<ApplicationDocument, Long> {

    /** 이전 제출본(is_current = FALSE)까지 모두 — 정렬은 서비스가 종류 · 올린 순서로 */
    List<ApplicationDocument> findAllByStoreApplicationId(Long storeApplicationId);

    /** 재제출 — 새로 낸 종류의 지금 서류 (이전 제출본으로 바꿀 대상) */
    List<ApplicationDocument> findAllByStoreApplicationIdAndCurrentTrueAndDocTypeIn(Long storeApplicationId,
            Collection<DocType> docTypes);
}
