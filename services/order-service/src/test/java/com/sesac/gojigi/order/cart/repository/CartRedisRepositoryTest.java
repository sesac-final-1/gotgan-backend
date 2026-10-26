package com.sesac.gojigi.order.cart.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class CartRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hash;

    @InjectMocks
    private CartRedisRepository repository;

    @Test
    @DisplayName("갱신 — cart:{userId} 를 지운 뒤 옵션 ID → 수량으로 다시 채운다, 빈 장바구니면 지우기만")
    void replace() {
        // given
        given(redis.opsForHash()).willReturn(hash);

        // when
        repository.replace(4L, Map.of(11L, 3));
        repository.replace(5L, Map.of());

        // then
        InOrder order = inOrder(redis, hash);
        order.verify(redis).delete("cart:4");
        order.verify(hash).putAll("cart:4", Map.of("11", "3"));
        verify(redis).delete("cart:5");
        verify(hash, never()).putAll("cart:5", Map.of());
    }

    @Test
    @DisplayName("폴백 — Redis 장애 · OOM 거절은 WARN 만 남기고 예외를 올리지 않는다")
    void replaceFallback() {
        assertThatCode(() -> repository.replaceFallback(4L, Map.of(11L, 3),
                new RedisSystemException("OOM command not allowed when used memory > 'maxmemory'", null)))
                .doesNotThrowAnyException();
    }
}
