package com.sesac.gojigi.user.point.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PointHistoryRepositoryIntegrationTest {

    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @Autowired
    private UserRepository userRepository;

    private User saveUser(String email) {
        return userRepository.save(User.signUp(email, "홍길동", null, null));
    }

    @Test
    @DisplayName("같은 (type, ref_key) 는 처리됨으로 판단하고, 중복 저장은 유니크 제약이 막는다")
    void existsByTypeAndRefKey_detectsDuplicateAndUniqueKeyBlocksInsert() {
        // given
        User user = saveUser("p1@gotgan.com");
        pointHistoryRepository.saveAndFlush(PointHistory.use(user, "20261015-0001", 1_000L, 0L, "saga-1"));

        // when · then
        assertThat(pointHistoryRepository.existsByTypeAndRefKey(PointType.USE, "saga-1")).isTrue();
        assertThat(pointHistoryRepository.existsByTypeAndRefKey(PointType.RESTORE, "saga-1")).isFalse();
        assertThatThrownBy(() -> pointHistoryRepository.saveAndFlush(
                PointHistory.use(user, "20261015-0001", 1_000L, 0L, "saga-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("내 포인트 이력은 최신순으로 페이징되고 다른 회원 이력은 섞이지 않는다")
    void findByUserId_returnsOwnHistoryLatestFirst() {
        // given
        User me = saveUser("p2@gotgan.com");
        User other = saveUser("p3@gotgan.com");
        pointHistoryRepository.save(PointHistory.earn(me, 1L, "20261015-0001", 100L, 100L, "evt-1-1"));
        pointHistoryRepository.save(PointHistory.earn(me, 2L, "20261015-0002", 200L, 300L, "evt-2-2"));
        pointHistoryRepository.save(PointHistory.earn(other, 3L, "20261015-0003", 300L, 300L, "evt-3-3"));
        pointHistoryRepository.flush();

        // when
        Page<PointHistory> page = pointHistoryRepository.findByUserId(me.getId(), PageRequest.of(0, 10));

        // then
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(PointHistory::getRefKey).containsExactly("evt-2-2", "evt-1-1");
    }

    @Test
    @DisplayName("상품주문 단위로 적립 · 회수 이력을 찾는다")
    void findByOrderItemIdAndTypeIn_filtersByOrderItemAndType() {
        // given
        User user = saveUser("p4@gotgan.com");
        pointHistoryRepository.save(PointHistory.earn(user, 10L, "20261015-0010", 500L, 500L, "evt-9-10"));
        pointHistoryRepository.save(PointHistory.reclaim(user, 10L, "20261015-0010", 500L, 0L, 0L, "claim-1-10"));
        pointHistoryRepository.save(PointHistory.earn(user, 11L, "20261015-0010", 300L, 300L, "evt-9-11"));
        pointHistoryRepository.flush();

        // when
        List<PointHistory> histories = pointHistoryRepository.findByOrderItemIdAndTypeIn(10L, List.of(PointType.EARN));

        // then
        assertThat(histories).extracting(PointHistory::getRefKey).containsExactly("evt-9-10");
    }
}
