package com.sesac.gojigi.store.apply.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class StoreApplicationTest {

    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 10, 27, 10, 0);
    private static final LocalDateTime DECIDED_AT = LocalDateTime.of(2026, 10, 28, 14, 30);

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"PENDING", "SUPPLEMENT"})
    @DisplayName("심사대기 · 보완요청 신청은 승인되고, 상태 변경 시각은 승인 시각 · 사유는 비워진다")
    void approve_whenPendingOrSupplement_becomesApproved(ApplicationStatus from) {
        // given
        StoreApplication application = application(from, "사업자등록증을 다시 올려 주세요.");

        // when
        application.approve(DECIDED_AT);

        // then
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(application.getStatusChangedAt()).isEqualTo(DECIDED_AT);
        assertThat(application.getStatusReason()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"APPROVED", "REJECTED"})
    @DisplayName("이미 승인 · 반려된 신청은 승인할 수 없어 409 APPLY_INVALID_STATUS 이고 상태는 그대로다")
    void approve_whenAlreadyDecided_throwsInvalidStatus(ApplicationStatus from) {
        // given
        StoreApplication application = application(from, null);

        // when · then
        assertThatThrownBy(() -> application.approve(DECIDED_AT))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ApplyErrorCode.APPLY_INVALID_STATUS);
        assertThat(application.getStatus()).isEqualTo(from);
        assertThat(application.getStatusChangedAt()).isEqualTo(SUBMITTED_AT);
    }

    @Test
    @DisplayName("새 신청은 심사대기로 시작한다")
    void build_whenSubmitted_startsPending() {
        // when
        StoreApplication application = application(ApplicationStatus.PENDING, null);

        // then
        assertThat(application.getStatus()).isEqualTo(ApplicationStatus.PENDING);
    }

    private StoreApplication application(ApplicationStatus status, String reason) {
        StoreApplication application = StoreApplication.builder()
                .userId(4L).categoryId(1L).bizType(BizType.INDIVIDUAL)
                .companyName("곳간상회").ownerName("홍길동").bizRegNo("000-00-00001").ecommerceRegNo("2026-서울성동-0000")
                .managerPhone("010-0000-1234").managerEmail("seller@example.com").businessAddress("서울시 성동구")
                .storeName("곳간상회").slug("gotgan").intro("<p>소개</p>")
                .representativeProducts(List.of(
                        new RepresentativeProduct("리넨 셔츠", "셔츠", 39000),
                        new RepresentativeProduct("리넨 팬츠", "팬츠", 45000)))
                .bankName("국민").accountNo("000000-00-000000").accountHolder("홍길동")
                .marketingAgreed(false).submittedAt(SUBMITTED_AT)
                .build();
        ReflectionTestUtils.setField(application, "status", status);
        ReflectionTestUtils.setField(application, "statusReason", reason);
        return application;
    }
}
