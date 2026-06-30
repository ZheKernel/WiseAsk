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

package com.nageoffer.ai.ragent.rag.core.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nageoffer.ai.ragent.rag.config.McpAuthorizationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthServerMcpAccessTokenProvider implements McpAccessTokenProvider {

    private static final String TOKEN_EXCHANGE_GRANT =
            "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String CLIENT_ASSERTION_TYPE =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final String ACCESS_TOKEN_TYPE =
            "urn:ietf:params:oauth:token-type:access_token";

    private final McpAuthorizationProperties properties;
    private final PrivateKeyJwtClientAssertionService assertionService;
    private final McpAccessTokenCache tokenCache;
    private final RestClient restClient;

    @Override
    public McpAccessToken serviceToken(String audience) {
        String cacheKey = "service|" + audience;
        return tokenCache.getOrLoad(cacheKey, () -> requestToken(serviceTokenForm()));
    }

    @Override
    public McpAccessToken userToken(String subjectToken, String audience, String role) {
        List<String> scopes = scopesForRole(role);
        String cacheKey = userCacheKey(subjectToken, audience, scopes);
        return tokenCache.getOrLoad(
                cacheKey,
                () -> requestToken(userTokenForm(subjectToken, audience, scopes))
        );
    }

    @Override
    public void invalidateUserToken(String subjectToken, String audience, String role) {
        tokenCache.evict(userCacheKey(subjectToken, audience, scopesForRole(role)));
    }

    private MultiValueMap<String, String> serviceTokenForm() {
        MultiValueMap<String, String> form = clientAuthenticationForm();
        form.add("grant_type", "client_credentials");
        form.add("scope", McpScopes.DISCOVER);
        return form;
    }

    private MultiValueMap<String, String> userTokenForm(
            String subjectToken,
            String audience,
            List<String> scopes) {
        MultiValueMap<String, String> form = clientAuthenticationForm();
        form.add("grant_type", TOKEN_EXCHANGE_GRANT);
        form.add("subject_token", subjectToken);
        form.add("subject_token_type", ACCESS_TOKEN_TYPE);
        form.add("requested_token_type", ACCESS_TOKEN_TYPE);
        form.add("audience", audience);
        form.add("scope", String.join(" ", scopes));
        return form;
    }

    private MultiValueMap<String, String> clientAuthenticationForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getClientId());
        form.add("client_assertion_type", CLIENT_ASSERTION_TYPE);
        form.add("client_assertion", assertionService.create());
        return form;
    }

    private McpAccessToken requestToken(MultiValueMap<String, String> form) {
        TokenResponse response = restClient.post()
                .uri(properties.getTokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (response == null
                || response.accessToken() == null
                || !"Bearer".equalsIgnoreCase(response.tokenType())) {
            throw new IllegalStateException("Auth Server returned an invalid token response");
        }
        long expiresIn = Math.max(1L, response.expiresIn());
        return new McpAccessToken(
                response.accessToken(),
                Instant.now().plusSeconds(expiresIn),
                parseScopes(response.scope())
        );
    }

    private List<String> scopesForRole(String role) {
        List<String> configured = "admin".equalsIgnoreCase(role)
                ? properties.getAdminScopes()
                : properties.getUserScopes();
        return configured.stream().distinct().sorted().toList();
    }

    private String userCacheKey(String subjectToken, String audience, List<String> scopes) {
        return digest(subjectToken) + "|" + audience + "|" + String.join(" ", scopes);
    }

    private String digest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash MCP subject token", ex);
        }
    }

    private Set<String> parseScopes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.trim().split("\\s+"))
                .filter(scope -> !scope.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn,
            String scope) {
    }
}
