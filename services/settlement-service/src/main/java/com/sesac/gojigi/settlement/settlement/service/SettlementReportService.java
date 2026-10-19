package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.settlement.dto.response.ReportUrlResponse;
import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 정산 명세서 다운로드 링크 (STL-10) — Lambda settlement-report 가 SettlementPaid 를 받아 만든 CSV 의 presigned GET.
 * 키는 결정론적 reports/{storeId}/{week}.csv 라 DynamoDB 조회 없이 만든다 (API 명세). 파일이 아직 없으면 링크를 열 때 404.
 */
@Slf4j
@Service
public class SettlementReportService {

    private static final Duration TTL = Duration.ofDays(7);

    private final S3Presigner s3Presigner;
    private final SettlementQueryService settlementQueryService;
    private final String bucket;
    private final Clock clock;

    public SettlementReportService(S3Presigner s3Presigner, SettlementQueryService settlementQueryService,
            @Value("${app.reports.bucket:}") String bucket, Clock clock) {
        this.s3Presigner = s3Presigner;
        this.settlementQueryService = settlementQueryService;
        this.bucket = bucket;
        this.clock = clock;
    }

    public ReportUrlResponse reportUrl(Long settlementId, Long storeId) {
        Settlement settlement = settlementQueryService.paidSettlementForStore(settlementId, storeId);
        String key = "reports/" + settlement.getStoreId() + "/" + new SettlementWeek(settlement.getWeekStart()).isoWeek() + ".csv";
        String url = s3Presigner.presignGetObject(b -> b.signatureDuration(TTL)
                .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toString();
        return new ReportUrlResponse(url, OffsetDateTime.now(clock).plus(TTL));
    }
}
