package com.sesac.gojigi.user.point.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.user.point.exception.PointErrorCode;
import com.sesac.gojigi.user.point.exception.PointException;
import com.sesac.gojigi.user.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PointHistoryTest {

    private final User user = User.signUp("p@gotgan.com", "홍길동", null, null);

    @Test
    @DisplayName("유형별 생성 — 사용(USE)은 상품주문 ID 가 없고, 회수(RECLAIM)만 미회수 차액을 남긴다")
    void factories_setTypeAndFields() {
        // when
        PointHistory earn = PointHistory.earn(user, 1L, "20261015-0001", 500L, 500L, "evt-1-1");
        PointHistory use = PointHistory.use(user, "20261015-0002", 300L, 200L, "saga-2");
        PointHistory restore = PointHistory.restore(user, 2L, "20261015-0002", 300L, 500L, "saga-2");
        PointHistory reclaim = PointHistory.reclaim(user, 1L, "20261015-0001", 500L, 200L, 0L, "claim-1-1");

        // then
        assertThat(earn.getType()).isEqualTo(PointType.EARN);
        assertThat(use.getType()).isEqualTo(PointType.USE);
        assertThat(use.getOrderItemId()).isNull();
        assertThat(restore.getType()).isEqualTo(PointType.RESTORE);
        assertThat(reclaim.getType()).isEqualTo(PointType.RECLAIM);
        assertThat(reclaim.getShortfallAmt()).isEqualTo(200L);
        assertThat(earn.getShortfallAmt()).isZero();
        assertThat(reclaim.getBalanceAfter()).isZero();
        assertThat(earn.getUser()).isSameAs(user);
    }

    @Test
    @DisplayName("금액이 0 이하면 이력을 만들 수 없다")
    void create_whenAmountNotPositive_throws() {
        assertThatThrownBy(() -> PointHistory.earn(user, 1L, "20261015-0001", 0L, 0L, "evt-1-1"))
                .isInstanceOf(PointException.class)
                .extracting(e -> ((PointException) e).getErrorCode())
                .isEqualTo(PointErrorCode.POINT_INVALID_AMOUNT);
        assertThatThrownBy(() -> PointHistory.use(user, "20261015-0001", -100L, 0L, "saga-1"))
                .isInstanceOf(PointException.class);
    }

    @Test
    @DisplayName("변동 후 잔액이나 미회수 차액이 음수면 이력을 만들 수 없다")
    void create_whenNegativeBalanceOrShortfall_throws() {
        assertThatThrownBy(() -> PointHistory.use(user, "20261015-0001", 100L, -1L, "saga-1"))
                .isInstanceOf(PointException.class);
        assertThatThrownBy(() -> PointHistory.reclaim(user, 1L, "20261015-0001", 100L, -1L, 0L, "claim-1-1"))
                .isInstanceOf(PointException.class);
    }

    @Test
    @DisplayName("적립 · 환원은 잔액 증가, 사용 · 회수는 잔액 감소")
    void pointType_isIncrease() {
        assertThat(PointType.EARN.isIncrease()).isTrue();
        assertThat(PointType.RESTORE.isIncrease()).isTrue();
        assertThat(PointType.USE.isIncrease()).isFalse();
        assertThat(PointType.RECLAIM.isIncrease()).isFalse();
    }

    @Test
    @DisplayName("에러 코드 문자열은 enum 상수명과 같다")
    void pointErrorCode_codeEqualsName() {
        for (PointErrorCode code : PointErrorCode.values()) {
            assertThat(code.getCode()).isEqualTo(code.name());
            assertThat(code.getMessage()).isNotBlank();
        }
        assertThat(PointErrorCode.POINT_INSUFFICIENT.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }
}
