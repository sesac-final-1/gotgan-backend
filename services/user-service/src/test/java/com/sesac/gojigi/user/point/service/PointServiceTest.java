package com.sesac.gojigi.user.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.point.dto.request.UsePointRequest;
import com.sesac.gojigi.user.point.repository.PointEarnBlockRepository;
import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.exception.PointErrorCode;
import com.sesac.gojigi.user.point.repository.PointHistoryRepository;
import com.sesac.gojigi.user.point.repository.PointUseSagaRepository;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 분기 규칙 — 회원 없음 · 멱등 충돌 · 탈퇴 회원 보상 (잔액 · 동시성은 PointServiceIntegrationTest) */
@ExtendWith(MockitoExtension.class)
class PointServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PointHistoryRepository pointHistoryRepository;
    @Mock
    private PointUseSagaRepository pointUseSagaRepository;
    @Mock
    private PointEarnBlockRepository pointEarnBlockRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private PointService pointService;

    @BeforeEach
    void setUp() {
        pointService = new PointService(userRepository, pointHistoryRepository, pointUseSagaRepository,
                pointEarnBlockRepository, processedEventRepository, transactionTemplate);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
    }

    private static User member(long id) {
        User user = User.signUp("p@gotgan.com", "포인트", null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("없는 회원 · 탈퇴 — 차감 0건이면 회원부터 확인해 USER_NOT_FOUND")
    void use_unknownUser() {
        given(userRepository.deductPoint(9L, 100L)).willReturn(0);
        given(userRepository.findByIdAndDeletedAtIsNull(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> pointService.use(new UsePointRequest("s", 9L, "o", 100L)))
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_NOT_FOUND);
    }

    @Test
    @DisplayName("같은 sagaId 인데 회원 · 금액이 다르면 409 POINT_USE_CONFLICT, 차감하지 않음")
    void use_sameSagaDifferentRequest_conflict() {
        PointHistory existing = PointHistory.use(member(4L), "o", 100L, 900L, "saga-1");
        given(pointHistoryRepository.findByTypeAndRefKey(PointType.USE, "saga-1")).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> pointService.use(new UsePointRequest("saga-1", 4L, "o", 200L)))
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_USE_CONFLICT);
        assertThatThrownBy(() -> pointService.use(new UsePointRequest("saga-1", 5L, "o", 100L)))
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_USE_CONFLICT);
        verify(userRepository, never()).deductPoint(anyLong(), anyLong());
    }

    @Test
    @DisplayName("사용 취소 — 탈퇴 회원이면 돌려줄 곳이 없어 0 으로 끝내고 이력도 남기지 않음")
    void cancelUse_withdrawnMember() {
        PointHistory use = PointHistory.use(member(4L), "o", 300L, 700L, "saga-2");
        given(pointHistoryRepository.findByTypeAndRefKey(PointType.RESTORE, "saga-2")).willReturn(Optional.empty());
        given(pointHistoryRepository.findByTypeAndRefKey(PointType.USE, "saga-2")).willReturn(Optional.of(use));
        given(userRepository.addPoint(4L, 300L)).willReturn(0);

        assertThat(pointService.cancelUse("saga-2").restoredAmount()).isZero();
        verify(pointHistoryRepository, never()).save(any());
    }
}
