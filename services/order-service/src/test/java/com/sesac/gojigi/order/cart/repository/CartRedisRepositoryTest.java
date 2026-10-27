package com.sesac.gojigi.order.cart.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.order.cart.repository.CartRedisRepository.CachedLine;
import java.util.LinkedHashMap;
import java.util.List;
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
    @DisplayName("갱신 — cart:{userId} 를 지운 뒤 옵션 ID → '수량:선택'으로 다시 채운다, 빈 장바구니면 지우기만")
    void replace() {
        // given
        given(redis.opsForHash()).willReturn(hash);

        // when
        repository.replace(4L, Map.of(11L, new CachedLine(3, true), 12L, new CachedLine(1, false)));
        repository.replace(5L, Map.of());

        // then
        InOrder order = inOrder(redis, hash);
        order.verify(redis).delete("cart:4");
        order.verify(hash).putAll("cart:4", Map.of("11", "3:1", "12", "1:0"));
        verify(redis).delete("cart:5");
        verify(hash, never()).putAll("cart:5", Map.of());
    }

    @Test
    @DisplayName("읽기 — 옵션 ID 순, 선택 표시 없는 예전 값은 선택된 것으로, 키가 없으면 미스(empty)")
    void find() {
        // given
        given(redis.opsForHash()).willReturn(hash);
        Map<Object, Object> stored = new LinkedHashMap<>();
        stored.put("12", "2:0");
        stored.put("11", "3");
        given(hash.entries("cart:4")).willReturn(stored);
        given(hash.entries("cart:5")).willReturn(Map.of());

        // when
        Map<Long, CachedLine> lines = repository.find(4L).orElseThrow();

        // then
        assertThat(List.copyOf(lines.keySet())).containsExactly(11L, 12L);
        assertThat(lines.get(11L)).isEqualTo(new CachedLine(3, true));
        assertThat(lines.get(12L)).isEqualTo(new CachedLine(2, false));
        assertThat(repository.find(5L)).isEmpty();
    }

    @Test
    @DisplayName("폴백 — Redis 장애 · OOM 거절은 WARN 만, 읽기 장애는 미스와 같다")
    void fallbacks() {
        RedisSystemException oom = new RedisSystemException("OOM command not allowed when used memory > 'maxmemory'", null);
        assertThatCode(() -> repository.replaceFallback(4L, Map.of(11L, new CachedLine(3, true)), oom))
                .doesNotThrowAnyException();
        assertThat(repository.findFallback(4L, oom)).isEmpty();
    }
}
