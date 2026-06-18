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

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;

/**
 * Auto-configures the reactive OAuth2 client (BFF) building blocks when a
 * {@link ReactiveClientRegistrationRepository} is present: an authorized-client manager supporting
 * {@code authorization_code}, {@code refresh_token} and {@code client_credentials}, and a WebClient
 * token-relay filter. All beans are {@link ConditionalOnMissingBean} so applications can override them.
 */
@AutoConfiguration
@ConditionalOnClass(ReactiveOAuth2AuthorizedClientManager.class)
public class OAuth2ClientAutoConfiguration {

    @Bean
    @ConditionalOnBean(ReactiveClientRegistrationRepository.class)
    @ConditionalOnMissingBean
    public ReactiveOAuth2AuthorizedClientService fireflyAuthorizedClientService(
            ReactiveClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryReactiveOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    @Bean
    @ConditionalOnBean(ReactiveClientRegistrationRepository.class)
    @ConditionalOnMissingBean
    public ReactiveOAuth2AuthorizedClientManager fireflyAuthorizedClientManager(
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            ReactiveOAuth2AuthorizedClientService authorizedClientService) {

        ReactiveOAuth2AuthorizedClientProvider provider = ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .clientCredentials()
                .build();

        AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /** WebClient filter that attaches the authorized client's bearer token to downstream calls (token relay). */
    @Bean
    @ConditionalOnBean(ReactiveOAuth2AuthorizedClientManager.class)
    @ConditionalOnMissingBean
    public ServerOAuth2AuthorizedClientExchangeFilterFunction fireflyOAuth2TokenRelayFilter(
            ReactiveOAuth2AuthorizedClientManager authorizedClientManager) {
        return new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
    }
}
