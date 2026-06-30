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
import com.nageoffer.ai.ragent.authserver.user.McpUserPrincipal;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class McpTokenClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final AuthServerProperties properties;
    private final RSAKey authServerRsaKey;

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }

        String clientId = context.getRegisteredClient().getClientId();
        context.getJwsHeader()
                .algorithm(SignatureAlgorithm.RS256)
                .keyId(authServerRsaKey.getKeyID());
        context.getClaims()
                .audience(List.of(properties.getOrderAudience()))
                .claim("client_id", clientId)
                .claim("azp", clientId)
                .id(UUID.randomUUID().toString())
                .notBefore(Instant.now());

        Authentication principal = context.getPrincipal();
        if (AuthorizationGrantType.TOKEN_EXCHANGE.equals(context.getAuthorizationGrantType())
                && principal != null
                && principal.getPrincipal() instanceof McpUserPrincipal userPrincipal) {
            context.getClaims()
                    .subject(userPrincipal.user().userId())
                    .claim("username", userPrincipal.user().username())
                    .claim("role", userPrincipal.user().role());
            return;
        }

        context.getClaims()
                .subject(clientId)
                .claim("username", clientId)
                .claim("role", "system");
    }
}
