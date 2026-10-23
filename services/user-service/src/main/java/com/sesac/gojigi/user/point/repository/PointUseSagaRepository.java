package com.sesac.gojigi.user.point.repository;

import com.sesac.gojigi.user.point.entity.PointUseSaga;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 포인트 사용 sagaId 표식 — INSERT · 조회만 (같은 sagaId 두 번째 INSERT 는 uk 충돌) */
public interface PointUseSagaRepository extends JpaRepository<PointUseSaga, Long> {

    Optional<PointUseSaga> findBySagaId(String sagaId);

    boolean existsBySagaId(String sagaId);
}
