/*
 * Copyright 2024-2026 Firefly Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fireflyframework.security.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the OAuth2 client BFF wiring performs a real {@code client_credentials} token exchange
 * against a stubbed authorization server (WireMock), using the auto-configuration's authorized-client
 * manager.
 */
class OAuth2ClientCredentialsIntegrationTest {

    private final OAuth2ClientAutoConfiguration autoConfiguration = new OAuth2ClientAutoConfiguration();

    private WireMockServer wireMock;

    @BeforeEach
    void startStubAuthServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo("/oauth2/token")).willReturn(
                okJson("{\"access_token\":\"abc123\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    @AfterEach
    void stopStubAuthServer() {
        wireMock.stop();
    }

    @Test
    void acquiresClientCredentialsAccessToken() {
        ClientRegistration registration = ClientRegistration.withRegistrationId("demo")
                .tokenUri("http://localhost:" + wireMock.port() + "/oauth2/token")
                .clientId("client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .build();

        ReactiveClientRegistrationRepository repository = new InMemoryReactiveClientRegistrationRepository(registration);
        ReactiveOAuth2AuthorizedClientService service = autoConfiguration.fireflyAuthorizedClientService(repository);
        ReactiveOAuth2AuthorizedClientManager manager = autoConfiguration.fireflyAuthorizedClientManager(repository, service);

        OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
                .withClientRegistrationId("demo")
                .principal("system")
                .build();

        OAuth2AuthorizedClient client = manager.authorize(request).block();

        assertThat(client).isNotNull();
        assertThat(client.getAccessToken().getTokenValue()).isEqualTo("abc123");
    }
}
