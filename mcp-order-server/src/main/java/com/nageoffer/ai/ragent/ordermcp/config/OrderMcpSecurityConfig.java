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

package com.nageoffer.ai.ragent.ordermcp.config;

import com.nageoffer.ai.ragent.ordermcp.security.OrderMcpIdentityBridgeFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@Slf4j
public class OrderMcpSecurityConfig {

    @Bean
    public JwtDecoder orderMcpJwtDecoder(OrderMcpAuthProperties properties) {
        if (isBlank(properties.getIssuer())
                || isBlank(properties.getJwkSetUri())
                || isBlank(properties.getAudience())) {
            throw new IllegalArgumentException(
                    "Order MCP issuer, JWK Set URI and audience must not be blank"
            );
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> issuerValidator =
                JwtValidators.createDefaultWithIssuer(properties.getIssuer());
        OAuth2TokenValidator<Jwt> audienceValidator = jwt ->
                jwt.getAudience().contains(properties.getAudience())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                "invalid_token",
                                "Required order-mcp audience is missing",
                                null
                        ));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                issuerValidator,
                audienceValidator
        ));
        return decoder;
    }

    @Bean
    public SecurityFilterChain orderMcpSecurityFilterChain(
            HttpSecurity http,
            JwtDecoder orderMcpJwtDecoder,
            OrderMcpIdentityBridgeFilter identityBridgeFilter) throws Exception {
        BearerTokenAuthenticationEntryPoint defaultEntryPoint =
                new BearerTokenAuthenticationEntryPoint();
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/.well-known/oauth-protected-resource").permitAll()
                        .requestMatchers("/mcp").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.decoder(orderMcpJwtDecoder))
                        .authenticationEntryPoint((request, response, exception) -> {
                            log.warn("[MCP-AUTH][TOKEN_REJECTED] Order MCP rejected Bearer "
                                            + "credential before tool execution, path={}, "
                                            + "reason={}",
                                    request.getRequestURI(),
                                    exception.getClass().getSimpleName());
                            defaultEntryPoint.commence(request, response, exception);
                        }))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .addFilterAfter(identityBridgeFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
