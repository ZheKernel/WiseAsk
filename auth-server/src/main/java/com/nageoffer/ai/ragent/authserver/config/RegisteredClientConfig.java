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

package com.nageoffer.ai.ragent.authserver.config;

import com.nageoffer.ai.ragent.authserver.security.McpScopes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Configuration
public class RegisteredClientConfig {

    @Bean
    public RegisteredClientRepository registeredClientRepository(AuthServerProperties properties) {
        List<RegisteredClient> clients = properties.getClients().stream()
                .map(client -> registeredClient(client, properties))
                .toList();
        if (clients.isEmpty()) {
            throw new IllegalStateException("At least one Auth Server client must be configured");
        }
        return new InMemoryRegisteredClientRepository(clients);
    }

    private RegisteredClient registeredClient(
            AuthServerProperties.Client client,
            AuthServerProperties properties) {
        if (isBlank(client.getClientId()) || isBlank(client.getJwkSetUri())) {
            throw new IllegalArgumentException("Client ID and JWK Set URI must not be blank");
        }

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(client.getClientId())
                .clientName(isBlank(client.getClientName()) ? client.getClientId() : client.getClientName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .clientSettings(ClientSettings.builder()
                        .jwkSetUrl(client.getJwkSetUri())
                        .tokenEndpointAuthenticationSigningAlgorithm(SignatureAlgorithm.RS256)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .accessTokenTimeToLive(Duration.ofSeconds(properties.getAccessTokenTtlSeconds()))
                        .build());

        client.getScopes().forEach(builder::scope);
        if (!client.getScopes().contains(McpScopes.DISCOVER)) {
            throw new IllegalArgumentException("Every MCP client must explicitly allow mcp:discover");
        }
        return builder.build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
