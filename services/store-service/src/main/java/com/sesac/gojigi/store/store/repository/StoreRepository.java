package com.sesac.gojigi.store.store.repository;

import com.sesac.gojigi.store.store.entity.Store;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {

    /** 활성 조회 — 소프트 삭제(deleted_at)된 스토어는 없는 것으로 본다 (컨벤션 3-5) */
    Optional<Store> findByIdAndDeletedAtIsNull(Long id);
}
