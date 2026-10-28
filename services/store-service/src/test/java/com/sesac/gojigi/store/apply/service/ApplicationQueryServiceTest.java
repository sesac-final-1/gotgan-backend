package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.sesac.gojigi.store.apply.dto.response.InternalApplicationSummaryResponse;
import com.sesac.gojigi.store.apply.entity.ApplicationStatus;
import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.StoreApplication;
import com.sesac.gojigi.store.apply.repository.StoreApplicationRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationQueryServiceTest {

    @Mock
    private StoreApplicationRepository storeApplicationRepository;

    @InjectMocks
    private ApplicationQueryService applicationQueryService;

    @Test
    @DisplayName("목록 한 줄은 접수번호를 계산하고 제출 · 상태 변경 시각을 +09:00 으로 바꾼다")
    void getList_withStatus_mapsSummary() {
        // given
        StoreApplication application = application(27L, LocalDateTime.of(2026, 10, 22, 14, 5, 11),
                LocalDateTime.of(2026, 10, 23, 10, 0));
        given(storeApplicationRepository.findAllByStatusOrderByTermsAgreedAtDescIdDesc(ApplicationStatus.SUPPLEMENT))
                .willReturn(List.of(application));

        // when
        List<InternalApplicationSummaryResponse> result = applicationQueryService.getList(ApplicationStatus.SUPPLEMENT);

        // then
        assertThat(result).containsExactly(new InternalApplicationSummaryResponse(27L, "AP-2610-027", "곳간상회", "홍길동",
                BizType.INDIVIDUAL, 1L, ExpectedMonthlySales.FROM_5M_TO_20M, ApplicationStatus.SUPPLEMENT,
                OffsetDateTime.parse("2026-10-22T14:05:11+09:00"), OffsetDateTime.parse("2026-10-23T10:00:00+09:00")));
        then(storeApplicationRepository).should(never()).findAllByOrderByTermsAgreedAtDescIdDesc();
    }

    @Test
    @DisplayName("status 가 null 이면 전체 조회 쿼리를 쓴다")
    void getList_whenStatusNull_queriesAll() {
        // given
        given(storeApplicationRepository.findAllByOrderByTermsAgreedAtDescIdDesc()).willReturn(List.of());

        // when
        List<InternalApplicationSummaryResponse> result = applicationQueryService.getList(null);

        // then
        assertThat(result).isEmpty();
        then(storeApplicationRepository).should(never()).findAllByStatusOrderByTermsAgreedAtDescIdDesc(null);
    }

    private StoreApplication application(Long id, LocalDateTime submittedAt, LocalDateTime statusChangedAt) {
        StoreApplication application = mock(StoreApplication.class);
        given(application.getId()).willReturn(id);
        given(application.getStoreName()).willReturn("곳간상회");
        given(application.getOwnerName()).willReturn("홍길동");
        given(application.getBizType()).willReturn(BizType.INDIVIDUAL);
        given(application.getCategoryId()).willReturn(1L);
        given(application.getExpectedMonthlySales()).willReturn(ExpectedMonthlySales.FROM_5M_TO_20M);
        given(application.getStatus()).willReturn(ApplicationStatus.SUPPLEMENT);
        given(application.getTermsAgreedAt()).willReturn(submittedAt);
        given(application.getStatusChangedAt()).willReturn(statusChangedAt);
        return application;
    }
}
