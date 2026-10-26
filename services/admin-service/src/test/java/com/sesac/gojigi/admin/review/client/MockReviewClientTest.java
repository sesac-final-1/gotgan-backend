package com.sesac.gojigi.admin.review.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MockReviewClientTest {

    @Test
    @DisplayName("review 배포 전 임시 — 어떤 ID 든 있는 리뷰, 블라인드 상태 · 사유만 기억하고 해제하면 지운다")
    void blindStateInMemory() {
        MockReviewClient mock = new MockReviewClient(JsonMapper.builder().build());

        assertThat(mock.get(31L).path("blinded").asBoolean()).isFalse();
        assertThat(mock.blind(31L, true, "욕설").path("blindReason").asString()).isEqualTo("욕설");
        assertThat(mock.get(31L).path("blinded").asBoolean()).isTrue();
        assertThat(mock.blind(31L, false, null).path("blinded").asBoolean()).isFalse();
    }
}
