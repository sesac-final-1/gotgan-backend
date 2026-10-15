package com.sesac.gojigi.user.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ProcessedEventRepositoryIntegrationTest {

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    @DisplayName("처리한 eventId 는 처리됨으로 판단하고, 같은 eventId 재저장은 유니크 제약이 막는다")
    void existsByEventId_detectsProcessedAndUniqueKeyBlocksDuplicate() {
        // given
        ProcessedEvent saved = processedEventRepository.saveAndFlush(
                new ProcessedEvent("0b6f3c1e-1111-4a2b-9c3d-000000000001", "OrderPaid"));

        // when · then
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getEventType()).isEqualTo("OrderPaid");
        assertThat(processedEventRepository.existsByEventId(saved.getEventId())).isTrue();
        assertThat(processedEventRepository.existsByEventId("0b6f3c1e-1111-4a2b-9c3d-000000000002")).isFalse();
        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(
                new ProcessedEvent("0b6f3c1e-1111-4a2b-9c3d-000000000001", "OrderPaid")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
