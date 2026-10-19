package com.sesac.gojigi.user.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AccessTokenClaimsTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    static String token(String payloadJson) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    @DisplayName("sub · user_id · store_id 를 꺼내고 역할은 BUYER · SELLER · ADMIN 만 남긴다")
    void parse_extractsClaimsAndFiltersRoles() {
        // given
        String accessToken = token("{\"sub\":\"kc-1\",\"user_id\":2,\"store_id\":1,"
                + "\"roles\":[\"default-roles-gotgan\",\"offline_access\",\"BUYER\",\"uma_authorization\",\"SELLER\"]}");

        // when
        AccessTokenClaims claims = AccessTokenClaims.parse(accessToken, jsonMapper);

        // then
        assertThat(claims.keycloakId()).isEqualTo("kc-1");
        assertThat(claims.userId()).isEqualTo(2L);
        assertThat(claims.storeId()).isEqualTo(1L);
        assertThat(claims.roles()).containsExactly("BUYER", "SELLER");
    }

    @Test
    @DisplayName("구매자 토큰은 store_id 가 없고, user_id 가 없으면 null")
    void parse_whenClaimsMissing_returnsNulls() {
        // when
        AccessTokenClaims claims = AccessTokenClaims.parse(token("{\"sub\":\"kc-2\",\"store_id\":null}"), jsonMapper);

        // then
        assertThat(claims.userId()).isNull();
        assertThat(claims.storeId()).isNull();
        assertThat(claims.roles()).isEmpty();
    }

    @Test
    @DisplayName("JWT 형식이 아니면 연결되지 않은 계정으로 본다")
    void parse_whenMalformed_throws() {
        assertThatThrownBy(() -> AccessTokenClaims.parse("not-a-jwt", jsonMapper))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).getErrorCode())
                .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_NOT_LINKED);
        assertThatThrownBy(() -> AccessTokenClaims.parse(null, jsonMapper))
                .isInstanceOf(AuthException.class);
    }
}
