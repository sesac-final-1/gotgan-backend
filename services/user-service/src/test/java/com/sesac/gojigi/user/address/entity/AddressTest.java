package com.sesac.gojigi.user.address.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.user.user.entity.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AddressTest {

    private static User userWithId(long id) {
        User user = User.signUp("user" + id + "@gotgan.com", "홍길동", null, null);
        ReflectionTestUtils.setField(user, "id", id);   // 저장 전 엔티티라 PK 를 직접 지정 (소유권 검사용)
        return user;
    }

    private static Address address(User user, boolean isDefault) {
        return Address.builder()
                .user(user).label("집").receiverName("홍길동").receiverPhone("010-1234-5678")
                .zipcode("06236").address1("서울 강남구 테헤란로 1").address2("101호").isDefault(isDefault)
                .build();
    }

    @Test
    @DisplayName("배송지 정보를 수정한다")
    void update_changesFields() {
        // given
        Address address = address(userWithId(1L), false);

        // when
        address.update("회사", "김철수", "010-9999-0000", "04524", "서울 중구 세종대로 110", "5층");

        // then
        assertThat(address.getLabel()).isEqualTo("회사");
        assertThat(address.getReceiverName()).isEqualTo("김철수");
        assertThat(address.getReceiverPhone()).isEqualTo("010-9999-0000");
        assertThat(address.getZipcode()).isEqualTo("04524");
        assertThat(address.getAddress1()).isEqualTo("서울 중구 세종대로 110");
        assertThat(address.getAddress2()).isEqualTo("5층");
    }

    @Test
    @DisplayName("기본 배송지로 지정하고 해제한다")
    void markDefaultAndUnmarkDefault_toggleDefault() {
        // given
        Address address = address(userWithId(1L), false);

        // when · then
        address.markDefault();
        assertThat(address.isDefault()).isTrue();

        address.unmarkDefault();
        assertThat(address.isDefault()).isFalse();
    }

    @Test
    @DisplayName("기본 배송지를 삭제하면 기본도 함께 해제된다")
    void softDelete_whenDefault_clearsDefault() {
        // given
        Address address = address(userWithId(1L), true);
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 0);

        // when
        address.softDelete(now);

        // then
        assertThat(address.isDeleted()).isTrue();
        assertThat(address.getDeletedAt()).isEqualTo(now);
        assertThat(address.isDefault()).isFalse();
    }

    @Test
    @DisplayName("소유한 회원만 true — 다른 회원의 배송지 접근 차단")
    void isOwnedBy_trueOnlyForOwner() {
        // given
        Address address = address(userWithId(1L), false);

        // when · then
        assertThat(address.isOwnedBy(1L)).isTrue();
        assertThat(address.isOwnedBy(2L)).isFalse();
        assertThat(address.isOwnedBy(null)).isFalse();
    }
}
