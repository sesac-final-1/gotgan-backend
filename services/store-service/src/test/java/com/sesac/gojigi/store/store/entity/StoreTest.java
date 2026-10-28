package com.sesac.gojigi.store.store.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
