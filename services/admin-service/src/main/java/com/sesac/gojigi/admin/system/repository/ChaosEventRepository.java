package com.sesac.gojigi.admin.system.repository;

import com.sesac.gojigi.admin.system.entity.ChaosEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChaosEventRepository extends JpaRepository<ChaosEvent, Long> {

    /** 타임라인 — 최신 20건 */
    List<ChaosEvent> findTop20ByOrderByIdDesc();

    /** 재시도 제한 · 복구 판정 — 그 서비스의 마지막 내리기 */
    Optional<ChaosEvent> findFirstByServiceNameOrderByIdDesc(String serviceName);

    /** 상태 조회(2초 폴링) — 서비스마다 마지막 내리기 1행씩, 한 번에 */
    @Query("select e from ChaosEvent e where e.id in (select max(x.id) from ChaosEvent x group by x.serviceName)")
    List<ChaosEvent> findLatestPerService();
}
