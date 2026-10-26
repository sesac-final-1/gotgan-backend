package com.sesac.gojigi.admin.report.repository;

import com.sesac.gojigi.admin.report.entity.ModerationAction;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

    List<ModerationAction> findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType targetType, Long targetId);

    List<ModerationAction> findAllByOrderByIdDesc();
}
