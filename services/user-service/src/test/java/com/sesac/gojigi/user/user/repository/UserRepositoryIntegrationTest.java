package com.sesac.gojigi.user.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.user.entity.User;
import java.time.LocalDateTime;
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
class UserRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    private User saveUserWithPoint(String email, long point) {
        User user = userRepository.save(User.signUp(email, "홍길동", null, "010-1234-5678"));
        if (point > 0) {
            userRepository.addPoint(user.getId(), point);
        }
        return user;
    }

    @Test
    @DisplayName("잔액이 충분하면 포인트를 차감하고 1건을 반환한다")
    void deductPoint_whenBalanceSufficient_deductsAndReturnsOne() {
        // given
        User user = saveUserWithPoint("a@gotgan.com", 1_000L);

        // when
        int updated = userRepository.deductPoint(user.getId(), 700L);

        // then
        assertThat(updated).isEqualTo(1);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPointBalance()).isEqualTo(300L);
    }

    @Test
    @DisplayName("잔액이 모자라면 차감하지 않고 0건을 반환한다 — 잔액은 음수가 되지 않는다")
    void deductPoint_whenBalanceInsufficient_returnsZeroAndKeepsBalance() {
        // given
        User user = saveUserWithPoint("b@gotgan.com", 500L);

        // when
        int updated = userRepository.deductPoint(user.getId(), 501L);

        // then
        assertThat(updated).isZero();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPointBalance()).isEqualTo(500L);
    }

    @Test
    @DisplayName("탈퇴한 회원은 포인트를 적립 · 차감할 수 없다")
    void addPointAndDeductPoint_whenUserDeleted_returnsZero() {
        // given
        User user = saveUserWithPoint("c@gotgan.com", 100L);
        User found = userRepository.findById(user.getId()).orElseThrow();
        found.softDelete(LocalDateTime.now());
        userRepository.flush();

        // when · then
        assertThat(userRepository.addPoint(user.getId(), 50L)).isZero();
        assertThat(userRepository.deductPoint(user.getId(), 50L)).isZero();
    }

    @Test
    @DisplayName("구매확정 금액을 누적한다")
    void addConfirmedAmount_accumulates() {
        // given
        User user = saveUserWithPoint("d@gotgan.com", 0L);

        // when
        userRepository.addConfirmedAmount(user.getId(), 30_000L);
        userRepository.addConfirmedAmount(user.getId(), 20_000L);

        // then
        assertThat(userRepository.findById(user.getId()).orElseThrow().getTotalConfirmedAmt()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("이메일 중복 검사는 탈퇴 회원도 포함한다 (uk_users_email)")
    void existsByEmail_includesDeletedUser() {
        // given
        User user = saveUserWithPoint("e@gotgan.com", 0L);
        userRepository.findById(user.getId()).orElseThrow().softDelete(LocalDateTime.now());
        userRepository.flush();

        // when · then
        assertThat(userRepository.existsByEmail("e@gotgan.com")).isTrue();
        assertThat(userRepository.findByIdAndDeletedAtIsNull(user.getId())).isEmpty();
    }
}
