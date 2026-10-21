package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.settlement.settlement.dto.response.ReportUrlResponse;
import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.exception.SettlementErrorCode;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 정산 명세서 다운로드 링크 (STL-10) — Lambda settlement-report 가 SettlementPaid 를 받아 만든 CSV 의 presigned GET.
 * 키는 결정론적 reports/{storeId}/{week}.csv 라 DynamoDB 조회 없이 만든다 (API 명세).
 *
 * 유효 기간 10분 · 클릭할 때마다 발급: 클러스터에서는 IRSA 임시 자격 증명으로 서명하는데, 이런 URL 은 서명에 쓴 세션이 끝나면
 * 함께 만료된다(최대 1시간). "7일 유효"로 내주면 실제로는 금방 403 이 되므로 짧게 내주고 프론트가 버튼을 누를 때마다 받는다.
 * 발급 전에 파일이 있는지(HeadObject) 확인 — Lambda 가 아직 만들지 않았으면 S3 오류 화면 대신 409 SETTLE_REPORT_NOT_READY.
 */
@Slf4j
@Service
public class SettlementReportService {

    static final Duration TTL = Duration.ofMinutes(10);

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final SettlementQueryService settlementQueryService;
    private final String bucket;
    private final Clock clock;

    public SettlementReportService(S3Presigner s3Presigner, S3Client s3Client, SettlementQueryService settlementQueryService,
            @Value("${app.reports.bucket:}") String bucket, Clock clock) {
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.settlementQueryService = settlementQueryService;
        this.bucket = bucket;
        this.clock = clock;
    }

    public ReportUrlResponse reportUrl(Long settlementId, Long storeId) {
        Settlement settlement = settlementQueryService.paidSettlementForStore(settlementId, storeId);
        String key = "reports/" + settlement.getStoreId() + "/" + new SettlementWeek(settlement.getWeekStart()).isoWeek() + ".csv";
        requireExists(key, settlementId);
        String url = s3Presigner.presignGetObject(b -> b.signatureDuration(TTL)
                .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toString();
        return new ReportUrlResponse(url, OffsetDateTime.now(clock).plus(TTL));
    }

    private void requireExists(String key, Long settlementId) {
        try {
            s3Client.headObject(b -> b.bucket(bucket).key(key));
        } catch (NoSuchKeyException e) {
            throw notYet(key, settlementId);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {   // HeadObject 는 본문이 없어 NoSuchKey 대신 404 S3Exception 으로 올 때가 많다
                throw notYet(key, settlementId);
            }
            log.error("명세서 확인 실패 - settlementId={}, key={}, status={}", settlementId, key, e.statusCode(), e);
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "명세서 저장소에 접근하지 못했습니다.");
        } catch (SdkException e) {
            log.error("명세서 확인 실패 - settlementId={}, key={}", settlementId, key, e);
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "명세서 저장소에 접근하지 못했습니다.");
        }
    }

    private static BusinessException notYet(String key, Long settlementId) {
        log.info("명세서 아직 없음 — Lambda 생성 전 settlementId={}, key={}", settlementId, key);
        return new BusinessException(SettlementErrorCode.SETTLE_REPORT_NOT_READY, "명세서를 만들고 있습니다. 잠시 후 다시 시도해 주세요.");
    }
}
