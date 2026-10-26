package com.sesac.gojigi.admin.report.repository;

import com.sesac.gojigi.admin.report.entity.Report;
import com.sesac.gojigi.admin.report.entity.ReportStatus;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, Long> {

    boolean existsByTargetTypeAndTargetIdAndReporterId(ReportTargetType targetType, Long targetId, Long reporterId);

    /** 관리자 목록 — 데모 규모라 전부 읽어 대상별로 묶는다 (컨벤션 4-7 전체 1회 로드) */
    List<Report> findAllByOrderByIdDesc();

    List<Report> findByTargetTypeOrderByIdDesc(ReportTargetType targetType);

    List<Report> findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType targetType, Long targetId);

    List<Report> findByTargetTypeAndTargetIdAndStatus(ReportTargetType targetType, Long targetId, ReportStatus status);
}
