package com.sesac.gojigi.user.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class BlockedUserRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> values;

    @Test
    @DisplayName("blocked:user:{userId} 에 사유를 토큰 수명(TTL)만큼 저장한다 — gateway 와 같은 키")
    void block_setsKeyWithTokenLifetime() {
        // given
        given(redis.opsForValue()).willReturn(values);
        BlockedUserRedisRepository repository = new BlockedUserRedisRepository(redis, Duration.ofMinutes(30));

        // when
        repository.block(7L, BlockedUserRedisRepository.WITHDRAWN);

        // then
        verify(values).set("blocked:user:7", "withdrawn", Duration.ofMinutes(30));
        assertThat(repository.ttl()).isEqualTo(Duration.ofMinutes(30));
    }
}
