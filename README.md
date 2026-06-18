# Firefly Framework - Security OAuth2 Client

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)
[![Spring Security](https://img.shields.io/badge/Spring%20Security-6.x-brightgreen.svg)](https://spring.io/projects/spring-security)

> Reactive OAuth2/OIDC client (BFF) for Spring WebFlux. A single auto-configuration turns any Firefly web application into an OAuth2 client that can obtain and refresh tokens via `authorization_code` (+PKCE), `client_credentials`, and `refresh_token`, and relay those tokens onto downstream calls — no client wiring code required.

---

## Table of Contents

- [Overview](#overview)
- [Where it sits in the platform](#where-it-sits-in-the-platform)
- [What it provides](#what-it-provides)
- [Key types](#key-types)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Token flow](#token-flow)
- [Testing](#testing)
- [License](#license)

## Overview

This module is the **client-side binding** of the Firefly hexagonal security platform. Where `security-resource-server` makes a service *validate* inbound bearer tokens, this module makes a service *acquire* outbound tokens — acting as an OAuth2/OIDC client (a Backend-for-Frontend, BFF) that authenticates a user or itself against an authorization server and carries the resulting access token to upstream APIs.

It is deliberately thin and product-agnostic. It contributes no controllers, no login pages, and no opinionated client registrations; it wires Spring Security 6's reactive OAuth2 client primitives onto the framework so that the common grant types work out of the box. Every bean it contributes is `@ConditionalOnMissingBean` and gated on the application actually providing a `ReactiveClientRegistrationRepository`, so the module stays inert until there is a client to configure, and any single piece can be overridden without forking it.

The whole module is one `@AutoConfiguration` class — `OAuth2ClientAutoConfiguration` — that assembles a `ReactiveOAuth2AuthorizedClientManager` supporting authorization-code, refresh, and client-credentials grants, plus a token-relay `WebClient` filter.

## Where it sits in the platform

The security platform is layered hexagonally; dependencies point inward, and provider/protocol bindings attach as outboard adapters:

```
security-api  →  security-spi  →  security-core  →  security-webflux  →  security-resource-server   (inbound tokens)
 (ports +         (driven           (neutral          (reactive          └ security-oauth2-client    (outbound tokens:
  domain)          ports)            engine)            Spring Security      this module — Spring         this module)
                                                        bindings)            Security 6 OAuth2 client
                                                                             wiring + token relay)
```

- **`security-resource-server`** is the inbound counterpart: it validates bearer tokens presented to this service.
- **This module** is the outbound counterpart: it obtains bearer tokens from an authorization server and relays them to downstream services.

Unlike the rest of the platform, this binding is intentionally decoupled from the framework's internal ports (`KeyManagementPort`, `PolicyDecisionPort`, …): a client obtains tokens from an external IdP rather than minting or deciding on them, so the module depends only on Spring Security's OAuth2 client artifacts and Spring WebFlux. It imports no vendor SDK and contributes nothing unless a `ReactiveClientRegistrationRepository` is present.

## What it provides

`OAuth2ClientAutoConfiguration` (active `@ConditionalOnClass(ReactiveOAuth2AuthorizedClientManager.class)`) contributes the following, each `@ConditionalOnBean(ReactiveClientRegistrationRepository.class)` and `@ConditionalOnMissingBean`:

- **An authorized-client service.** `fireflyAuthorizedClientService` — an `InMemoryReactiveOAuth2AuthorizedClientService` over the application's `ReactiveClientRegistrationRepository`, holding authorized clients (access/refresh tokens) per registration and principal.
- **An authorized-client manager.** `fireflyAuthorizedClientManager` — an `AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager` configured with a `ReactiveOAuth2AuthorizedClientProvider` built via `ReactiveOAuth2AuthorizedClientProviderBuilder` for `.authorizationCode()`, `.refreshToken()`, and `.clientCredentials()`. This is the single entry point that resolves (and, when expired, refreshes) an `OAuth2AuthorizedClient` for a given registration. The service-based manager is suited to machine-to-machine and BFF flows where authorization is driven outside an inbound request.
- **A token-relay filter.** `fireflyOAuth2TokenRelayFilter` — a `ServerOAuth2AuthorizedClientExchangeFilterFunction` wrapping the authorized-client manager. Registered on a `WebClient`, it attaches the authorized client's bearer token to downstream calls and triggers acquisition/refresh as needed.

## Key types

| Type | Role |
| --- | --- |
| `OAuth2ClientAutoConfiguration` | `@AutoConfiguration` entry point; assembles the authorized-client service, manager, and token-relay filter. |
| `ReactiveOAuth2AuthorizedClientManager` (bean `fireflyAuthorizedClientManager`) | Resolves and refreshes `OAuth2AuthorizedClient`s for `authorization_code`, `refresh_token`, and `client_credentials`. |
| `ReactiveOAuth2AuthorizedClientService` (bean `fireflyAuthorizedClientService`) | In-memory store of authorized clients per registration/principal. |
| `ServerOAuth2AuthorizedClientExchangeFilterFunction` (bean `fireflyOAuth2TokenRelayFilter`) | `WebClient` filter that attaches the bearer token to downstream calls. |

Application-supplied input: a `ReactiveClientRegistrationRepository` (e.g. `InMemoryReactiveClientRegistrationRepository`) holding one or more `ClientRegistration`s. All Spring Security OAuth2 client types (`OAuth2AuthorizeRequest`, `OAuth2AuthorizedClient`, `AuthorizationGrantType`) come from `spring-security-oauth2-client`.

The auto-configuration is registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Requirements

- Java 21+
- Spring Boot 3.x, Spring Security 6.x
- A reactive web stack (Spring WebFlux)
- A `ReactiveClientRegistrationRepository` bean describing your client registration(s); without one, the module contributes nothing.

## Installation

The version is managed by the Firefly parent/BOM, so you can usually omit it. In a Firefly application this module is pulled in transitively when OAuth2-client behavior is required; depend on it directly when binding an OAuth2 client standalone:

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-security-oauth2-client</artifactId>
</dependency>
```

If you are not inheriting the Firefly parent, pin the version explicitly:

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-security-oauth2-client</artifactId>
    <version>26.06.01</version>
</dependency>
```

## Quick Start

Declare a client registration (here a `client_credentials` registration). Spring Boot's standard `spring.security.oauth2.client.registration.*` / `.provider.*` properties produce a `ReactiveClientRegistrationRepository`, which switches the auto-configuration on:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          ledger-api:
            authorization-grant-type: client_credentials
            client-id: firefly-bff
            client-secret: ${LEDGER_CLIENT_SECRET}
        provider:
          ledger-api:
            token-uri: https://idp.example.com/oauth2/token
```

Build a `WebClient` with the token-relay filter and pin a registration; the bearer token is acquired (and refreshed) and attached automatically:

```java
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
class LedgerClient {

    private final WebClient webClient;

    LedgerClient(WebClient.Builder builder,
                 ServerOAuth2AuthorizedClientExchangeFilterFunction tokenRelay) {
        tokenRelay.setDefaultClientRegistrationId("ledger-api");
        this.webClient = builder
                .baseUrl("https://ledger.internal")
                .filter(tokenRelay)
                .build();
    }

    Mono<String> balances() {
        return webClient.get().uri("/api/balances").retrieve().bodyToMono(String.class);
    }
}
```

For machine-to-machine code paths you can also drive the manager directly:

```java
OAuth2AuthorizeRequest request = OAuth2AuthorizeRequest
        .withClientRegistrationId("ledger-api")
        .principal("system")
        .build();

Mono<String> token = authorizedClientManager.authorize(request)
        .map(client -> client.getAccessToken().getTokenValue());
```

## Configuration

This module defines no `firefly.*` properties of its own. Client registrations and providers are configured with Spring Boot's standard OAuth2 client keys under `spring.security.oauth2.client.registration.<id>` and `spring.security.oauth2.client.provider.<id>` (or by contributing a `ReactiveClientRegistrationRepository` bean directly). Each registration's `authorization-grant-type` selects the flow; the bundled manager already supports `authorization_code`, `refresh_token`, and `client_credentials`.

## Token flow

```
WebClient call (registrationId pinned)
   → fireflyOAuth2TokenRelayFilter (ServerOAuth2AuthorizedClientExchangeFilterFunction)
   → fireflyAuthorizedClientManager.authorize(OAuth2AuthorizeRequest)
       → ReactiveOAuth2AuthorizedClientProvider (authorization_code | refresh_token | client_credentials)
       → token endpoint exchange / refresh as needed
   → OAuth2AuthorizedClient cached in fireflyAuthorizedClientService
   → Authorization: Bearer <access_token> attached to the downstream request
```

A cached, still-valid authorized client is reused; an expired token is refreshed (or re-acquired for `client_credentials`) before the downstream call proceeds.

## Testing

The module ships an integration test, `OAuth2ClientCredentialsIntegrationTest`, that performs a **real `client_credentials` token exchange against a live HTTP token endpoint** — a WireMock server stubbing `POST /oauth2/token` to return an `access_token`. It builds a `ClientRegistration` (grant type `CLIENT_CREDENTIALS`) pointed at the WireMock port, wires it through the actual auto-configuration's `fireflyAuthorizedClientService` and `fireflyAuthorizedClientManager`, and calls `manager.authorize(...)`:

- It asserts a non-null `OAuth2AuthorizedClient` is returned, and that `client.getAccessToken().getTokenValue()` equals the token the stub issued (`abc123`).

This proves the wiring end to end over a real network exchange: the manager performs an HTTP POST to the token endpoint, parses the token response, and stores the authorized client — rather than mocking the exchange away.

## License

Copyright 2024-2026 Firefly Software Foundation.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
