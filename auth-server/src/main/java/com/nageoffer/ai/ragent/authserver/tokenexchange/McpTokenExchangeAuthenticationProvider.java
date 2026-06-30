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
import com.nageoffer.ai.ragent.authserver.user.McpUserPrincipal;
import com.nageoffer.ai.ragent.authserver.security.McpScopes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClaimAccessor;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenExchangeAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpTokenExchangeAuthenticationProvider implements AuthenticationProvider {

    private static final String ACCESS_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:access_token";
    private static final String JWT_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:jwt";
    private static final String INVALID_TARGET = "invalid_target";

    private final SaTokenSubjectVerifier subjectVerifier;
    private final AuthServerProperties properties;
    private final OAuth2AuthorizationService authorizationService;
    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2TokenExchangeAuthenticationToken tokenExchange =
                (OAuth2TokenExchangeAuthenticationToken) authentication;
        OAuth2ClientAuthenticationToken clientPrincipal = authenticatedClient(tokenExchange);
        RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();

        if (!registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.TOKEN_EXCHANGE)) {
            throw error(OAuth2ErrorCodes.UNAUTHORIZED_CLIENT, "Client cannot use token exchange");
        }
        validateTokenTypes(tokenExchange);
        validateAudience(tokenExchange);

        AuthUser user = subjectVerifier.verify(tokenExchange.getSubjectToken())
                .orElseThrow(() -> error(OAuth2ErrorCodes.INVALID_GRANT, "Invalid subject token"));
        Set<String> authorizedScopes = authorizeScopes(tokenExchange, registeredClient, user);
        Authentication userAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                new McpUserPrincipal(user),
                "",
                Set.of()
        );

        DefaultOAuth2TokenContext tokenContext = DefaultOAuth2TokenContext.builder()
                .registeredClient(registeredClient)
                .principal(userAuthentication)
                .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                .authorizedScopes(authorizedScopes)
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .authorizationGrant(tokenExchange)
                .build();

        OAuth2Token generatedToken = tokenGenerator.generate(tokenContext);
        if (generatedToken == null) {
            throw error(OAuth2ErrorCodes.SERVER_ERROR, "Failed to generate access token");
        }
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                generatedToken.getTokenValue(),
                generatedToken.getIssuedAt(),
                generatedToken.getExpiresAt(),
                authorizedScopes
        );

        OAuth2Authorization.Builder authorizationBuilder = OAuth2Authorization
                .withRegisteredClient(registeredClient)
                .principalName(user.userId())
                .authorizationGrantType(AuthorizationGrantType.TOKEN_EXCHANGE)
                .authorizedScopes(authorizedScopes);
        ClaimAccessor tokenClaims = generatedToken instanceof ClaimAccessor claimAccessor
                ? claimAccessor
                : null;
        if (tokenClaims != null) {
            authorizationBuilder.token(accessToken, metadata ->
                    metadata.put(
                            OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                            tokenClaims.getClaims()
                    ));
        } else {
            authorizationBuilder.accessToken(accessToken);
        }
        authorizationService.save(authorizationBuilder.build());

        String tokenId = tokenClaims == null ? null : tokenClaims.getClaimAsString("jti");
        log.info("Issued MCP token, clientId={}, subject={}, audience={}, scopes={}, jti={}",
                registeredClient.getClientId(), user.userId(), properties.getOrderAudience(),
                authorizedScopes, tokenId);
        return new OAuth2AccessTokenAuthenticationToken(
                registeredClient,
                clientPrincipal,
                accessToken,
                null,
                Map.of("issued_token_type", ACCESS_TOKEN_TYPE)
        );
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2TokenExchangeAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private OAuth2ClientAuthenticationToken authenticatedClient(
            OAuth2TokenExchangeAuthenticationToken tokenExchange) {
        Object principal = tokenExchange.getPrincipal();
        if (principal instanceof OAuth2ClientAuthenticationToken client
                && client.isAuthenticated()
                && client.getRegisteredClient() != null) {
            return client;
        }
        throw error(OAuth2ErrorCodes.INVALID_CLIENT, "Client authentication is required");
    }

    private void validateTokenTypes(OAuth2TokenExchangeAuthenticationToken tokenExchange) {
        if (!ACCESS_TOKEN_TYPE.equals(tokenExchange.getSubjectTokenType())) {
            throw error(OAuth2ErrorCodes.INVALID_REQUEST, "Unsupported subject token type");
        }
        String requestedType = tokenExchange.getRequestedTokenType();
        if (requestedType != null
                && !ACCESS_TOKEN_TYPE.equals(requestedType)
                && !JWT_TOKEN_TYPE.equals(requestedType)) {
            throw error(OAuth2ErrorCodes.INVALID_REQUEST, "Unsupported requested token type");
        }
        if (tokenExchange.getActorToken() != null || !tokenExchange.getResources().isEmpty()) {
            throw error(OAuth2ErrorCodes.INVALID_REQUEST, "Actor and resource parameters are not supported");
        }
    }

    private void validateAudience(OAuth2TokenExchangeAuthenticationToken tokenExchange) {
        if (tokenExchange.getAudiences().size() != 1
                || !tokenExchange.getAudiences().contains(properties.getOrderAudience())) {
            throw error(INVALID_TARGET, "Unsupported audience");
        }
    }

    private Set<String> authorizeScopes(
            OAuth2TokenExchangeAuthenticationToken tokenExchange,
            RegisteredClient registeredClient,
            AuthUser user) {
        Set<String> requested = tokenExchange.getScopes().isEmpty()
                ? Set.of(McpScopes.ORDER_READ_SELF)
                : tokenExchange.getScopes();
        if (!registeredClient.getScopes().containsAll(requested)) {
            throw error(OAuth2ErrorCodes.INVALID_SCOPE, "Client requested an unregistered scope");
        }

        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(McpScopes.ORDER_READ_SELF);
        if ("admin".equalsIgnoreCase(user.role())) {
            allowed.add(McpScopes.ORDER_READ_ANY);
        }
        allowed.retainAll(requested);
        if (allowed.isEmpty()) {
            throw error(OAuth2ErrorCodes.INVALID_SCOPE, "User is not allowed to use the requested scope");
        }
        return Set.copyOf(allowed);
    }

    private OAuth2AuthenticationException error(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }
}
