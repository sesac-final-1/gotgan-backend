package com.sesac.gojigi.store.store.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StoreTest {

    @Test
    @DisplayName("승인으로 만든 스토어는 신청서 값을 그대로 갖고, 운영중 · 기본 배송비 3,000원 · 무료배송 없음으로 시작한다")
    void open_whenApproved_startsActiveWithDefaultShipping() {
        // when
        Store store = Store.open(27L, 4L, "곳간상회", "gotgan", "<p>소개</p>", "010-0000-1234");

        // then
        assertThat(store.getStoreApplicationId()).isEqualTo(27L);
        assertThat(store.getUserId()).isEqualTo(4L);
        assertThat(store.getStoreName()).isEqualTo("곳간상회");
        assertThat(store.getSlug()).isEqualTo("gotgan");
        assertThat(store.getIntro()).isEqualTo("<p>소개</p>");
        assertThat(store.getCsPhone()).isEqualTo("010-0000-1234");
        assertThat(store.getStatus()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(store.getShippingFee()).isEqualTo(3000L);
        assertThat(store.getFreeShippingThreshold()).isNull();
        assertThat(store.getCourier()).isNull();
    }

    @Test
    @DisplayName("관리자 정지 — 상태 · 사유가 바뀌고 true, 이미 그 상태면 아무것도 안 바꾸고 false")
    void changeStatusByAdmin_whenDifferentOrSame_changesOnlyOnce() {
        // given
        Store store = Store.open(27L, 4L, "곳간상회", "gotgan", null, "010-0000-1234");

        // when · then
        assertThat(store.changeStatusByAdmin(StoreStatus.SUSPENDED, "허위 광고")).isTrue();
        assertThat(store.getStatus()).isEqualTo(StoreStatus.SUSPENDED);
        assertThat(store.getStatusReason()).isEqualTo("허위 광고");

        assertThat(store.changeStatusByAdmin(StoreStatus.SUSPENDED, "다른 사유")).isFalse();
        assertThat(store.getStatusReason()).isEqualTo("허위 광고");

        assertThat(store.changeStatusByAdmin(StoreStatus.ACTIVE, "소명 확인")).isTrue();
        assertThat(store.getStatus()).isEqualTo(StoreStatus.ACTIVE);
        assertThat(store.getStatusReason()).isEqualTo("소명 확인");
    }

    @Test
    @DisplayName("폐점 스토어는 정지 · 해제 모두 409 STORE_INVALID_STATUS_TRANSITION 이고 상태가 그대로다")
    void changeStatusByAdmin_whenClosed_throwsInvalidTransition() {
        // given
        Store store = Store.open(27L, 4L, "곳간상회", "gotgan", null, "010-0000-1234");
        ReflectionTestUtils.setField(store, "status", StoreStatus.CLOSED);

        // when · then
        for (StoreStatus target : new StoreStatus[]{StoreStatus.ACTIVE, StoreStatus.SUSPENDED}) {
            assertThatThrownBy(() -> store.changeStatusByAdmin(target, "사유"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(StoreErrorCode.STORE_INVALID_STATUS_TRANSITION);
        }
        assertThat(store.getStatus()).isEqualTo(StoreStatus.CLOSED);
    }

    @Test
    @DisplayName("폐점 — 정지 중이어도 CLOSED 가 되고 정지 사유는 비운다, 이미 폐점이면 false")
    void close_whenSuspended_closesAndClearsReason() {
        // given
        Store store = Store.open(27L, 4L, "곳간상회", "gotgan", null, "010-0000-1234");
        store.changeStatusByAdmin(StoreStatus.SUSPENDED, "허위 광고");

        // when · then
        assertThat(store.close()).isTrue();
        assertThat(store.getStatus()).isEqualTo(StoreStatus.CLOSED);
        assertThat(store.getStatusReason()).isNull();
        assertThat(store.close()).isFalse();
    }

    @Test
    @DisplayName("설정 수정 — 값이 들어가고 색은 소문자, 공백뿐인 택배사 · 출고 안내는 null, 폐점이면 409")
    void updateSettings_setsValuesAndRejectsClosed() {
        // given
        Store store = Store.open(27L, 4L, "곳간상회", "gotgan", null, "010-0000-1234");

        // when
        store.updateSettings("<p>소개</p>", "stores/1/a.jpg", "#5B6B4A", "02-0000-1234", 0L, null, "  ", " 당일 출고 ");

        // then
        assertThat(store.getColor()).isEqualTo("#5b6b4a");
        assertThat(store.getCourier()).isNull();
        assertThat(store.getDispatchNote()).isEqualTo("당일 출고");
        assertThat(store.getShippingFee()).isZero();
        assertThat(store.getStoreName()).isEqualTo("곳간상회");   // 이름은 그대로
        store.close();
        assertThatThrownBy(() -> store.updateSettings(null, null, "#5b6b4a", "02-0000-1234", 0L, null, null, null))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(StoreErrorCode.STORE_CLOSED);
    }
}
