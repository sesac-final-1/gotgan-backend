package com.sesac.gojigi.user.auth.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KeycloakPropertiesTest {

    @Test
    @DisplayName("realm 주소를 만들고, toString 에 client-secret 을 노출하지 않는다")
    void toString_masksClientSecret() {
        KeycloakProperties properties = new KeycloakProperties("http://keycloak:8080", "gotgan", "gotgan-backend", "s3cr3t");

        assertThat(properties.realmUrl()).isEqualTo("http://keycloak:8080/realms/gotgan");
        assertThat(properties.toString()).doesNotContain("s3cr3t").contains("clientSecret=***");
    }
}
