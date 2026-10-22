package com.sesac.gojigi.user.address.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.address.dto.request.CreateAddressRequest;
import com.sesac.gojigi.user.address.dto.response.AddressResponse;
import com.sesac.gojigi.user.address.entity.Address;
import com.sesac.gojigi.user.address.exception.AddressErrorCode;
import com.sesac.gojigi.user.address.exception.AddressException;
import com.sesac.gojigi.user.address.repository.AddressRepository;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 MySQL — 기본 배송지 규칙(첫 배송지 · main=true 시 기존 해제)과 동시 등록에서 기본이 1개로 유지되는지.
 * 서비스 트랜잭션이 실제로 커밋돼야 해서 테스트 트랜잭션으로 감싸지 않는다 (끝나고 직접 정리).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, AddressService.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AddressServiceIntegrationTest {

    @Autowired
    private AddressService addressService;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private UserRepository userRepository;

    @AfterEach
    void cleanUp() {
        addressRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    private Long member() {
        return userRepository.save(User.signUp("addr-" + System.nanoTime() + "@gotgan.com", "회원", null, null)).getId();
    }

    private static CreateAddressRequest request(String label, boolean main) {
        return new CreateAddressRequest(label, " 김곳간 ", "01012345678", "06236", " 서울특별시 강남구 테헤란로 152 ", " ", main);
    }

    private List<String> defaultLabels(Long userId) {
        return addressRepository.findActiveByUserId(userId).stream().filter(Address::isDefault).map(Address::getLabel).toList();
    }

    @Test
    @DisplayName("첫 배송지는 main=false 여도 기본, 값은 다듬어 저장 (공백 제거 · 휴대폰 하이픈 · 빈 상세 주소 null)")
    void firstAddress_becomesDefault() {
        Long userId = member();

        AddressResponse home = addressService.create(userId, request("집", false));

        assertThat(home.main()).isTrue();
        assertThat(home.name()).isEqualTo("김곳간");
        assertThat(home.phone()).isEqualTo("010-1234-5678");
        assertThat(home.addr()).isEqualTo("서울특별시 강남구 테헤란로 152");
        assertThat(home.detail()).isNull();
    }

    @Test
    @DisplayName("두 번째부터 main=false 는 기본이 아니고, main=true 면 기존 기본을 해제하고 새 기본 (기본은 항상 1개)")
    void mainTrue_movesDefault() {
        Long userId = member();
        addressService.create(userId, request("집", false));

        AddressResponse office = addressService.create(userId, request("회사", false));
        assertThat(office.main()).isFalse();
        assertThat(defaultLabels(userId)).containsExactly("집");

        AddressResponse parents = addressService.create(userId, request("본가", true));
        assertThat(parents.main()).isTrue();
        assertThat(defaultLabels(userId)).containsExactly("본가");
        assertThat(addressRepository.findActiveByUserId(userId)).hasSize(3);
    }

    @Test
    @DisplayName("같은 회원이 기본 배송지를 동시에 5건 등록해도 기본은 1개 (회원 행 잠금)")
    void concurrentDefaults_keepOneDefault() throws Exception {
        Long userId = member();
        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<AddressResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String label = "주소" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return addressService.create(userId, request(label, true));
            }));
        }
        start.countDown();
        for (Future<AddressResponse> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertThat(addressRepository.findActiveByUserId(userId)).hasSize(5);
        assertThat(defaultLabels(userId)).hasSize(1);
    }

    @Test
    @DisplayName("최대 5개 — 6번째는 409 ADDRESS_LIMIT_EXCEEDED, 삭제한 배송지는 세지 않아 하나 지우면 다시 등록 가능")
    void limit_fiveActiveAddresses() {
        Long userId = member();
        for (int i = 0; i < AddressService.MAX_ADDRESSES; i++) {
            addressService.create(userId, request("주소" + i, false));
        }

        assertThatThrownBy(() -> addressService.create(userId, request("여섯째", false)))
                .extracting("errorCode").isEqualTo(AddressErrorCode.ADDRESS_LIMIT_EXCEEDED);

        Address oldest = addressRepository.findActiveByUserId(userId).getLast();
        oldest.softDelete(LocalDateTime.now());
        addressRepository.save(oldest);
        assertThat(addressService.create(userId, request("다시", false)).label()).isEqualTo("다시");
        assertThat(addressRepository.findActiveByUserId(userId)).hasSize(AddressService.MAX_ADDRESSES);
    }

    @Test
    @DisplayName("동시에 8건 등록해도 5개까지만 저장, 나머지는 ADDRESS_LIMIT_EXCEEDED (회원 행 잠금 안에서 개수 확인)")
    void limit_concurrentCreates() throws Exception {
        Long userId = member();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String label = "동시" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    addressService.create(userId, request(label, false));
                    return true;
                } catch (AddressException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int success = 0;
        for (Future<Boolean> f : futures) {
            success += f.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(success).isEqualTo(AddressService.MAX_ADDRESSES);
        assertThat(addressRepository.findActiveByUserId(userId)).hasSize(AddressService.MAX_ADDRESSES);
        assertThat(defaultLabels(userId)).hasSize(1);   // 첫 등록만 기본
    }

    @Test
    @DisplayName("탈퇴 회원 — USER_NOT_FOUND, 배송지 저장 안 함")
    void withdrawnMember_notFound() {
        Long userId = member();
        User user = userRepository.findById(userId).orElseThrow();
        user.softDelete(LocalDateTime.now());
        userRepository.save(user);

        assertThatThrownBy(() -> addressService.create(userId, request("집", true)))
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_NOT_FOUND);
        assertThat(addressRepository.count()).isZero();
    }
}
