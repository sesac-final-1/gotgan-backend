package com.sesac.gojigi.user.auth.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Keycloak 클라이언트 — 공통 RestClient.Builder(연결 2초 · 읽기 3초)를 복제해 realm 주소만 붙인다 */
@Configuration
public class KeycloakClientConfig {

    @Bean
    public KeycloakClient keycloakClient(RestClient.Builder restClientBuilder,
            @Value("${app.keycloak.base-url}") String baseUrl,
            @Value("${app.keycloak.realm}") String realm) {
        RestClient client = restClientBuilder.clone()
                .baseUrl(baseUrl + "/realms/" + realm)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build()
                .createClient(KeycloakClient.class);
    }
}
