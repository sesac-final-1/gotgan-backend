package com.sesac.gojigi.store.apply.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import com.sesac.gojigi.store.apply.service.ApplicationReviewService;
import com.sesac.gojigi.store.apply.service.SupplementDeadline;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

@ExtendWith(MockitoExtension.class)
class SupplementExpirySchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 18, 0);

    @Mock
    private StoreApplicationRepository storeApplicationRepository;
    @Mock
    private ApplicationReviewService applicationReviewService;

    private SupplementExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SupplementExpiryScheduler(storeApplicationRepository, applicationReviewService,
                new SupplementDeadline(Duration.ofDays(7)), Clock.fixed(NOW.atZone(KST).toInstant(), KST), 100);
    }

    @Test
    @DisplayName("보완요청이 지금 − 7일 이전인 신청을 100건까지 골라 건마다 자동 반려 — 한 건이 충돌해도 다음 건은 처리")
    void rejectExpired_rejectsEachAndContinuesOnConflict() {
        // given
        given(storeApplicationRepository.findExpiredSupplementIds(NOW.minusDays(7), Limit.of(100))).willReturn(List.of(27L, 28L, 29L));
        willThrow(new ApplyException(ApplyErrorCode.APPLY_INVALID_STATUS)).given(applicationReviewService).rejectExpiredSupplement(28L);

        // when
        scheduler.rejectExpired();

        // then
        verify(applicationReviewService).rejectExpiredSupplement(27L);
        verify(applicationReviewService).rejectExpiredSupplement(28L);
        verify(applicationReviewService).rejectExpiredSupplement(29L);
    }
}
