package com.sesac.gojigi.review.inquiry.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class UserSummaryResponseTest {

    @ParameterizedTest(name = "name={0}, nick={1} → {2}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
            "홍길동   | 곳간러버   | 곳간러버",
            "홍길동   | '  리넨 ' | 리넨",
            "홍길동   | '   '     | 홍*동",
            "남궁민수 | NULL      | 남**수",
            "홍길     | NULL      | 홍*",
            "홍       | NULL      | *",
            "NULL     | NULL      | *"
    })
    @DisplayName("닉네임이 있으면 닉네임, 없으면 실명 가운데를 가린다")
    void displayName_nickOrMaskedName(String name, String nick, String expected) {
        assertThat(new UserSummaryResponse(4L, name, nick).displayName()).isEqualTo(expected);
    }
}
