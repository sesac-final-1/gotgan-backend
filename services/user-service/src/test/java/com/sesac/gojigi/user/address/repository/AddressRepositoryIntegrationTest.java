package com.sesac.gojigi.user.address.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.address.entity.Address;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AddressRepositoryIntegrationTest {

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private UserRepository userRepository;

    private User saveUser(String email) {
        return userRepository.save(User.signUp(email, "홍길동", null, null));
    }

    private Address saveAddress(User user, String label, boolean isDefault) {
        return addressRepository.save(Address.builder()
                .user(user).label(label).receiverName("홍길동").receiverPhone("010-1234-5678")
                .zipcode("06236").address1("서울 강남구 테헤란로 1").isDefault(isDefault)
                .build());
    }

    @Test
    @DisplayName("내 배송지 목록은 기본 배송지가 먼저, 삭제한 배송지는 빠진다")
    void findActiveByUserId_ordersDefaultFirstAndExcludesDeleted() {
        // given
        User user = saveUser("addr1@gotgan.com");
        saveAddress(user, "회사", false);
        saveAddress(user, "집", true);
        Address deleted = saveAddress(user, "옛집", false);
        deleted.softDelete(LocalDateTime.now());
        addressRepository.flush();

        // when
        List<Address> addresses = addressRepository.findActiveByUserId(user.getId());

        // then
        assertThat(addresses).extracting(Address::getLabel).containsExactly("집", "회사");
    }

    @Test
    @DisplayName("다른 회원의 배송지는 조회되지 않는다")
    void findActiveByIdAndUserId_whenOtherUser_returnsEmpty() {
        // given
        User owner = saveUser("owner@gotgan.com");
        User other = saveUser("other@gotgan.com");
        Address address = saveAddress(owner, "집", true);

        // when · then
        assertThat(addressRepository.findActiveByIdAndUserId(address.getId(), owner.getId())).isPresent();
        assertThat(addressRepository.findActiveByIdAndUserId(address.getId(), other.getId())).isEmpty();
    }

    @Test
    @DisplayName("기본 배송지 해제는 그 회원의 기본만 해제한다")
    void clearDefault_clearsOnlyOwnDefault() {
        // given
        User me = saveUser("me@gotgan.com");
        User other = saveUser("you@gotgan.com");
        saveAddress(me, "집", true);
        saveAddress(other, "집", true);

        // when
        int updated = addressRepository.clearDefault(me.getId());

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(addressRepository.findDefaultByUserId(me.getId())).isEmpty();
        assertThat(addressRepository.findDefaultByUserId(other.getId())).isPresent();
    }
}
