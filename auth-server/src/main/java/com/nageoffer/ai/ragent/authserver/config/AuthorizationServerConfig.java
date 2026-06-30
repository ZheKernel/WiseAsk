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

import com.nageoffer.ai.ragent.authserver.client.ClientAssertionReplayStore;
import com.nageoffer.ai.ragent.authserver.token.McpTokenClaimsCustomizer;
import com.nageoffer.ai.ragent.authserver.tokenexchange.McpTokenExchangeAuthenticationProvider;
import com.nageoffer.ai.ragent.authserver.security.McpScopes;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.authentication.JwtClientAssertionAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.JwtClientAssertionDecoderFactory;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Set;

@Configuration
@RequiredArgsConstructor
public class AuthorizationServerConfig {

    private final AuthServerProperties properties;

    @Bean
    public OAuth2AuthorizationService authorizationService() {
        return new InMemoryOAuth2AuthorizationService();
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(properties.getIssuer())
                .build();
    }

    @Bean
    public OAuth2TokenGenerator<?> tokenGenerator(
            JWKSource<SecurityContext> authServerJwkSource,
            McpTokenClaimsCustomizer claimsCustomizer) {
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(authServerJwkSource);
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(claimsCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator,
                new OAuth2AccessTokenGenerator()
        );
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            RegisteredClientRepository registeredClientRepository,
            OAuth2AuthorizationService authorizationService,
            AuthorizationServerSettings authorizationServerSettings,
            OAuth2TokenGenerator<?> tokenGenerator,
            McpTokenExchangeAuthenticationProvider tokenExchangeProvider,
            ClientAssertionReplayStore replayStore) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();

        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, configurer -> configurer
                        .registeredClientRepository(registeredClientRepository)
                        .authorizationService(authorizationService)
                        .authorizationServerSettings(authorizationServerSettings)
                        .tokenGenerator(tokenGenerator)
                        .clientAuthentication(clientAuthentication ->
                                clientAuthentication.authenticationProviders(providers ->
                                        configureClientAuthenticationProviders(
                                                providers,
                                                replayStore
                                        )))
                        .tokenEndpoint(tokenEndpoint -> tokenEndpoint.authenticationProviders(
                                providers -> configureTokenProviders(providers, tokenExchangeProvider)
                        )))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServer.getEndpointsMatcher()));
        return http.build();
    }

    private void configureClientAuthenticationProviders(
            java.util.List<AuthenticationProvider> providers,
            ClientAssertionReplayStore replayStore) {
        providers.stream()
                .filter(JwtClientAssertionAuthenticationProvider.class::isInstance)
                .map(JwtClientAssertionAuthenticationProvider.class::cast)
                .forEach(provider -> {
                    JwtClientAssertionDecoderFactory decoderFactory =
                            new JwtClientAssertionDecoderFactory();
                    decoderFactory.setJwtValidatorFactory(registeredClient -> {
                        OAuth2TokenValidator<Jwt> defaultValidator =
                                JwtClientAssertionDecoderFactory.DEFAULT_JWT_VALIDATOR_FACTORY
                                        .apply(registeredClient);
                        return jwt -> {
                            OAuth2TokenValidatorResult result = defaultValidator.validate(jwt);
                            if (result.hasErrors()) {
                                return result;
                            }
                            if (jwt.getId() == null
                                    || jwt.getId().isBlank()
                                    || jwt.getExpiresAt() == null
                                    || !replayStore.markIfAbsent(
                                            registeredClient.getClientId(),
                                            jwt.getId(),
                                            jwt.getExpiresAt()
                                    )) {
                                return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                        OAuth2ErrorCodes.INVALID_TOKEN,
                                        "Client assertion is missing a valid unique jti",
                                        null
                                ));
                            }
                            return OAuth2TokenValidatorResult.success();
                        };
                    });
                    provider.setJwtDecoderFactory(decoderFactory);
                });
    }

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    private void configureTokenProviders(
            java.util.List<AuthenticationProvider> providers,
            McpTokenExchangeAuthenticationProvider tokenExchangeProvider) {
        providers.add(0, tokenExchangeProvider);
        providers.stream()
                .filter(OAuth2ClientCredentialsAuthenticationProvider.class::isInstance)
                .map(OAuth2ClientCredentialsAuthenticationProvider.class::cast)
                .forEach(provider -> provider.setAuthenticationValidator(
                        new OAuth2ClientCredentialsAuthenticationValidator()
                                .andThen(context -> {
                                    OAuth2ClientCredentialsAuthenticationToken authentication =
                                            context.getAuthentication();
                                    Set<String> scopes = authentication.getScopes();
                                    if (!Set.of(McpScopes.DISCOVER).equals(scopes)) {
                                        throw new OAuth2AuthenticationException(new OAuth2Error(
                                                OAuth2ErrorCodes.INVALID_SCOPE,
                                                "Client credentials can only request mcp:discover",
                                                null
                                        ));
                                    }
                                })
                ));
    }
}
