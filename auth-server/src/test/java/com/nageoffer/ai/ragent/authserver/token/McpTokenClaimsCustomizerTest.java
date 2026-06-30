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

package com.nageoffer.ai.ragent.authserver.token;

import com.nageoffer.ai.ragent.authserver.config.AuthServerProperties;
import com.nageoffer.ai.ragent.authserver.config.JwkSourceConfig;
import com.nageoffer.ai.ragent.authserver.user.AuthUser;
import com.nageoffer.ai.ragent.authserver.user.McpUserPrincipal;
import com.nageoffer.ai.ragent.authserver.security.McpScopes;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;

import java.util.Map;
import java.util.Set;

class McpTokenClaimsCustomizerTest {

    @Test
    void shouldSignAudienceBoundUserTokenWithAuthServerKey() {
        AuthServerProperties properties = new AuthServerProperties();
        properties.setIssuer("http://localhost:9200");
        properties.setOrderAudience("order-mcp");
        RSAKey rsaKey = new JwkSourceConfig().authServerRsaKey(properties);
        JwtGenerator generator = new JwtGenerator(new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey))
        ));
        generator.setJwtCustomizer(new McpTokenClaimsCustomizer(properties, rsaKey));
        RegisteredClient client = RegisteredClient.withId("client-id")
                .clientId("ragent")
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .scope(McpScopes.ORDER_READ_SELF)
                .build();
        OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(
                client,
                ClientAuthenticationMethod.PRIVATE_KEY_JWT,
                null
        );
        OAuth2TokenExchangeAuthenticationToken exchange =
                new OAuth2TokenExchangeAuthenticationToken(
                        "urn:ietf:params:oauth:token-type:access_token",
                        "sa-token",
                        "urn:ietf:params:oauth:token-type:access_token",
                        clientPrincipal,
                        null,
                        null,
                        Set.of(),
                        Set.of("order-mcp"),
                        Set.of(McpScopes.ORDER_READ_SELF),
                        Map.of()
                );
        UsernamePasswordAuthenticationToken userPrincipal =
                UsernamePasswordAuthenticationToken.authenticated(
                        new McpUserPrincipal(new AuthUser("user-1", "alice", "user")),
                        "",
                        Set.of()
                );

        Jwt jwt = generator.generate(DefaultOAuth2TokenContext.builder()
                .registeredClient(client)
                .principal(userPrincipal)
                .authorizationServerContext(context(properties.getIssuer()))
                .authorizedScopes(Set.of(McpScopes.ORDER_READ_SELF))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .authorizationGrant(exchange)
                .build());

        Assertions.assertNotNull(jwt);
        Assertions.assertEquals(SignatureAlgorithm.RS256, jwt.getHeaders().get("alg"));
        Assertions.assertEquals(rsaKey.getKeyID(), jwt.getHeaders().get("kid"));
        Assertions.assertEquals("user-1", jwt.getSubject());
        Assertions.assertEquals("alice", jwt.getClaimAsString("username"));
        Assertions.assertEquals("user", jwt.getClaimAsString("role"));
        Assertions.assertEquals("ragent", jwt.getClaimAsString("client_id"));
        Assertions.assertEquals(Set.of("order-mcp"), Set.copyOf(jwt.getAudience()));
    }

    private AuthorizationServerContext context(String issuer) {
        return new AuthorizationServerContext() {
            @Override
            public String getIssuer() {
                return issuer;
            }

            @Override
            public AuthorizationServerSettings getAuthorizationServerSettings() {
                return AuthorizationServerSettings.builder().issuer(issuer).build();
            }
        };
    }
}
