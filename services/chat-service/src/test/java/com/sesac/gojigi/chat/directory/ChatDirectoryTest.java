package com.sesac.gojigi.chat.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 이름 · 스토어 상태 조회 — user · store 는 가짜 서버, 시계를 움직여 캐시 만료 확인 */
class ChatDirectoryTest {

    private MockRestServiceServer users;
    private MockRestServiceServer stores;
    private MockRestServiceServer settlements;
    private Instant now = Instant.parse("2026-10-27T05:00:00Z");
    private ChatDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder userBuilder = RestClient.builder().baseUrl("http://user");
        RestClient.Builder storeBuilder = RestClient.builder().baseUrl("http://store");
        users = MockRestServiceServer.bindTo(userBuilder).build();
        stores = MockRestServiceServer.bindTo(storeBuilder).build();
        RestClient.Builder settlementBuilder = RestClient.builder().baseUrl("http://settlement");
        settlements = MockRestServiceServer.bindTo(settlementBuilder).build();
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        directory = new ChatDirectory(userBuilder.build(), storeBuilder.build(), settlementBuilder.build(), clock);
    }

    @Test
    @DisplayName("회원 이름 — 한 번 받으면 10분 동안 다시 부르지 않는다")
    void userNameIsCached() {
        users.expect(times(2), requestTo("http://user/internal/users/10"))
                .andRespond(withSuccess("{\"code\":\"SUCCESS\",\"data\":{\"userId\":10,\"name\":\"김지연\"}}", MediaType.APPLICATION_JSON));

        assertThat(directory.userName(10L)).isEqualTo("김지연");
        assertThat(directory.userName(10L)).isEqualTo("김지연");
        now = now.plusSeconds(ChatDirectory.NAME_TTL_MINUTES * 60);
        assertThat(directory.userName(10L)).isEqualTo("김지연");
        assertThat(directory.userName(null)).isNull();
        users.verify();
    }

    @Test
    @DisplayName("스토어 — 이름 · 운영 상태(ACTIVE 만 열림), 없는 스토어는 막고, 조회 실패는 막지 않는다(10초 뒤 다시)")
    void storeStatus() {
        stores.expect(once(), requestTo("http://store/internal/stores/1"))
                .andRespond(withSuccess("{\"data\":{\"storeId\":1,\"storeName\":\"리넨하우스\",\"status\":\"ACTIVE\"}}", MediaType.APPLICATION_JSON));
        stores.expect(once(), requestTo("http://store/internal/stores/2"))
                .andRespond(withSuccess("{\"data\":{\"storeId\":2,\"storeName\":\"정지상점\",\"status\":\"SUSPENDED\"}}", MediaType.APPLICATION_JSON));
        stores.expect(once(), requestTo("http://store/internal/stores/3")).andRespond(withResourceNotFound());
        stores.expect(times(2), requestTo("http://store/internal/stores/4")).andRespond(withServerError());

        assertThat(directory.storeName(1L)).isEqualTo("리넨하우스");
        assertThat(directory.storeOpen(1L)).isTrue();
        assertThat(directory.storeOpen(2L)).isFalse();
        assertThat(directory.storeName(3L)).isNull();
        assertThat(directory.storeOpen(3L)).isFalse();
        assertThat(directory.storeOpen(4L)).isTrue();
        now = now.plus(ChatDirectory.FAILURE_TTL);
        assertThat(directory.storeName(4L)).isNull();
        stores.verify();
    }

    @Test
    @DisplayName("회원 없음(404) · 권한 오류 · 빈 응답은 이름 없음")
    void missingUser() {
        users.expect(requestTo("http://user/internal/users/1")).andRespond(withResourceNotFound());
        users.expect(requestTo("http://user/internal/users/2")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        users.expect(requestTo("http://user/internal/users/3")).andRespond(withSuccess("{\"data\":null}", MediaType.APPLICATION_JSON));

        assertThat(directory.userName(1L)).isNull();
        assertThat(directory.userName(2L)).isNull();
        assertThat(directory.userName(3L)).isNull();
        users.verify();
    }

    @Test
    @DisplayName("정산 카드 — 상세 응답의 settlement(storeId · week · payoutAmt), 없으면 null")
    void settlement() {
        settlements.expect(once(), requestTo("http://settlement/internal/settlements/31")).andRespond(withSuccess(
                "{\"data\":{\"settlement\":{\"id\":31,\"storeId\":5,\"week\":\"2026-W43\",\"payoutAmt\":1234000},\"items\":[]}}", MediaType.APPLICATION_JSON));
        settlements.expect(once(), requestTo("http://settlement/internal/settlements/32")).andRespond(withResourceNotFound());

        assertThat(directory.settlement(31L)).isEqualTo(new ChatDirectory.SettlementInfo(5L, "2026-W43", 1_234_000));
        assertThat(directory.settlement(31L).week()).isEqualTo("2026-W43");   // 캐시
        assertThat(directory.settlement(32L)).isNull();
        settlements.verify();
    }

    @Test
    @DisplayName("이름 가리기 — 김지연 → 김*연, 남궁민수 → 남**수, 홍길 → 홍*, 한 글자 · null 은 그대로")
    void mask() {
        assertThat(ChatDirectory.mask("김지연")).isEqualTo("김*연");
        assertThat(ChatDirectory.mask("남궁민수")).isEqualTo("남**수");
        assertThat(ChatDirectory.mask("홍길")).isEqualTo("홍*");
        assertThat(ChatDirectory.mask("김")).isEqualTo("김");
        assertThat(ChatDirectory.mask(null)).isNull();
    }
}
