/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.authserver.tokenexchange;

import com.nageoffer.ai.ragent.authserver.config.AuthServerProperties;
import com.nageoffer.ai.ragent.authserver.session.SaTokenSubjectVerifier;
import com.nageoffer.ai.ragent.authserver.user.AuthUser;
import com.nageoffer.ai.ragent.authserver.security.McpScopes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class McpTokenExchangeAuthenticationProviderTest {

    private static final String ACCESS_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:access_token";

    private AuthUser verifiedUser;
    private final SaTokenSubjectVerifier subjectVerifier = new SaTokenSubjectVerifier(null) {
        @Override
        public Optional<AuthUser> verify(String subjectToken) {
            return Optional.ofNullable(verifiedUser);
        }
    };
    private final OAuth2TokenGenerator<Jwt> tokenGenerator = context -> jwt();
    private final AuthServerProperties properties = new AuthServerProperties();
    private final RegisteredClient registeredClient = RegisteredClient.withId("client-id")
            .clientId("ragent")
            .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
            .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
            .scope(McpScopes.ORDER_READ_SELF)
            .scope(McpScopes.ORDER_READ_ANY)
            .build();
    private McpTokenExchangeAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        properties.setOrderAudience("order-mcp");
        provider = new McpTokenExchangeAuthenticationProvider(
                subjectVerifier,
                properties,
                new InMemoryOAuth2AuthorizationService(),
                tokenGenerator
        );
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return "http://localhost:9200";
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return AuthorizationServerSettings.builder()
                        .issuer(getIssuer())
                        .build();
            }
        });
    }

    @AfterEach
    void tearDown() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    void shouldDowngradeNormalUserToSelfReadScope() {
        verifiedUser = new AuthUser("user-1", "alice", "user");

        OAuth2AccessTokenAuthenticationToken result =
                (OAuth2AccessTokenAuthenticationToken) provider.authenticate(exchange(
                        Set.of(McpScopes.ORDER_READ_SELF, McpScopes.ORDER_READ_ANY),
                        Set.of("order-mcp")
                ));

        Assertions.assertEquals(
                Set.of(McpScopes.ORDER_READ_SELF),
                result.getAccessToken().getScopes()
        );
    }

    @Test
    void shouldGrantAdminSelfAndAnyScopes() {
        verifiedUser = new AuthUser("admin-1", "admin", "admin");

        OAuth2AccessTokenAuthenticationToken result =
                (OAuth2AccessTokenAuthenticationToken) provider.authenticate(exchange(
                        Set.of(McpScopes.ORDER_READ_SELF, McpScopes.ORDER_READ_ANY),
                        Set.of("order-mcp")
                ));

        Assertions.assertEquals(
                Set.of(McpScopes.ORDER_READ_SELF, McpScopes.ORDER_READ_ANY),
                result.getAccessToken().getScopes()
        );
    }

    @Test
    void shouldRejectWrongAudience() {
        verifiedUser = new AuthUser("user-1", "alice", "user");

        OAuth2AuthenticationException exception = Assertions.assertThrows(
                OAuth2AuthenticationException.class,
                () -> provider.authenticate(exchange(
                        Set.of(McpScopes.ORDER_READ_SELF),
                        Set.of("another-service")
                ))
        );

        Assertions.assertEquals("invalid_target", exception.getError().getErrorCode());
    }

    private OAuth2TokenExchangeAuthenticationToken exchange(
            Set<String> scopes,
            Set<String> audiences) {
        OAuth2ClientAuthenticationToken clientPrincipal =
                new OAuth2ClientAuthenticationToken(
                        registeredClient,
                        ClientAuthenticationMethod.PRIVATE_KEY_JWT,
                        null
                );
        return new OAuth2TokenExchangeAuthenticationToken(
                ACCESS_TOKEN_TYPE,
                "sa-token",
                ACCESS_TOKEN_TYPE,
                clientPrincipal,
                null,
                null,
                Set.of(),
                audiences,
                scopes,
                Map.of()
        );
    }

    private Jwt jwt() {
        Instant now = Instant.now();
        return new Jwt(
                "signed-access-token",
                now,
                now.plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of(
                        "sub", "user-1",
                        "aud", Set.of("order-mcp"),
                        "scope", Set.of(McpScopes.ORDER_READ_SELF)
                )
        );
    }
}
