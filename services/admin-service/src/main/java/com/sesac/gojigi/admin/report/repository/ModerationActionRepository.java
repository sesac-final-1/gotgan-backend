package com.sesac.gojigi.admin.report.repository;

import com.sesac.gojigi.admin.report.entity.ModerationAction;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    List<ModerationAction> findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType targetType, Long targetId);

    /**
     * 신고 목록에 나온 대상들의 마지막 조치만 — 대상마다 가장 큰 id 1행 (11/3 점검: 조치 이력 전체를 읽어 메모리에서 고르던 것).
     * 조치 이력은 INSERT 만 쌓이므로 목록을 열 때 읽는 양이 이력 크기가 아니라 대상 수에 비례하게
     */
    @Query("""
            select a from ModerationAction a
             where a.targetType = :type and a.targetId in :ids
               and a.id = (select max(b.id) from ModerationAction b where b.targetType = a.targetType and b.targetId = a.targetId)
            """)
    List<ModerationAction> findLatestByTargets(@Param("type") ReportTargetType type, @Param("ids") Collection<Long> ids);
}
