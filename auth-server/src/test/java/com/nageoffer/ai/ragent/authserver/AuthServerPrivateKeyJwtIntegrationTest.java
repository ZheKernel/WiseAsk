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

package com.nageoffer.ai.ragent.authserver;

import com.nageoffer.ai.ragent.authserver.client.ClientAssertionReplayStore;
import com.nageoffer.ai.ragent.authserver.session.SaTokenSubjectVerifier;
import com.nageoffer.ai.ragent.authserver.user.AuthUser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootTest(
        classes = {
                AuthServerApplication.class,
                AuthServerPrivateKeyJwtIntegrationTest.SubjectVerifierTestConfig.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.address=localhost",
                "spring.datasource.hikari.initialization-fail-timeout=-1"
        })
@TestExecutionListeners(
        listeners = {
                DependencyInjectionTestExecutionListener.class,
                DirtiesContextTestExecutionListener.class
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class AuthServerPrivateKeyJwtIntegrationTest {

    private static final RSAKey CLIENT_KEY = generateClientKey();
    private static final HttpServer CLIENT_JWKS_SERVER = startClientJwksServer();
    private static final String CLIENT_JWKS_URI =
            "http://localhost:" + CLIENT_JWKS_SERVER.getAddress().getPort() + "/jwks";

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @DynamicPropertySource
    static void clientJwksProperties(DynamicPropertyRegistry registry) {
        registry.add("auth-server.clients[0].client-id", () -> "ragent");
        registry.add("auth-server.clients[0].client-name", () -> "Ragent");
        registry.add("auth-server.clients[0].jwk-set-uri", () -> CLIENT_JWKS_URI);
        registry.add("auth-server.clients[0].scopes[0]", () -> "mcp:discover");
        registry.add("auth-server.clients[0].scopes[1]", () -> "order:read:self");
        registry.add("auth-server.clients[0].scopes[2]", () -> "order:read:any");
    }

    @AfterAll
    static void stopClientJwksServer() {
        CLIENT_JWKS_SERVER.stop(0);
    }

    @Test
    void shouldAuthenticateClientAssertionAndIssueDiscoveryToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "mcp:discover");
        ResponseEntity<Map> response = postToken(form);

        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), response.toString());
        Assertions.assertEquals("Bearer", response.getBody().get("token_type"));
        Assertions.assertEquals("mcp:discover", response.getBody().get("scope"));
        SignedJWT token = verifiedAccessToken(response);
        Assertions.assertEquals("ragent", claim(token, "sub"));
        Assertions.assertEquals("system", claim(token, "role"));
        Assertions.assertEquals(java.util.List.of("order-mcp"), claim(token, "aud"));
    }

    @Test
    void shouldExchangeSaTokenForUserScopedOrderToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.add("subject_token", "valid-sa-token");
        form.add("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        form.add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
        form.add("audience", "order-mcp");
        form.add("scope", "order:read:self order:read:any");

        ResponseEntity<Map> response = postToken(form);

        Assertions.assertTrue(response.getStatusCode().is2xxSuccessful(), response.toString());
        Assertions.assertEquals(
                Set.of("order:read:self", "order:read:any"),
                Set.of(String.valueOf(response.getBody().get("scope")).split("\\s+"))
        );
        SignedJWT token = verifiedAccessToken(response);
        Assertions.assertEquals("user-1", claim(token, "sub"));
        Assertions.assertEquals("alice", claim(token, "username"));
        Assertions.assertEquals("admin", claim(token, "role"));
        Assertions.assertEquals("ragent", claim(token, "client_id"));
    }

    @Test
    void shouldRejectInvalidSubjectToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.add("subject_token", "invalid-sa-token");
        form.add("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        form.add("audience", "order-mcp");
        form.add("scope", "order:read:self");

        ResponseEntity<Map> response = postToken(form);

        Assertions.assertEquals(400, response.getStatusCode().value());
        Assertions.assertEquals("invalid_grant", response.getBody().get("error"));
    }

    @Test
    void shouldRejectReplayedClientAssertion() {
        String assertion = clientAssertion();

        ResponseEntity<Map> first = postToken(discoveryTokenForm(), assertion);
        ResponseEntity<Map> replay = postToken(discoveryTokenForm(), assertion);

        Assertions.assertTrue(first.getStatusCode().is2xxSuccessful(), first.toString());
        Assertions.assertEquals(401, replay.getStatusCode().value());
        Assertions.assertEquals("invalid_client", replay.getBody().get("error"));
    }

    private ResponseEntity<Map> postToken(MultiValueMap<String, String> form) {
        return postToken(form, clientAssertion());
    }

    private ResponseEntity<Map> postToken(
            MultiValueMap<String, String> form,
            String assertion) {
        form.add("client_id", "ragent");
        form.add(
                "client_assertion_type",
                "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
        );
        form.add("client_assertion", assertion);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/oauth2/token",
                new HttpEntity<>(form, headers),
                Map.class
        );
    }

    private MultiValueMap<String, String> discoveryTokenForm() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "mcp:discover");
        return form;
    }

    private SignedJWT verifiedAccessToken(ResponseEntity<Map> response) {
        try {
            String value = String.valueOf(response.getBody().get("access_token"));
            SignedJWT token = SignedJWT.parse(value);
            ResponseEntity<Map> jwksResponse = restTemplate.getForEntity(
                    "http://localhost:" + port + "/oauth2/jwks",
                    Map.class
            );
            RSAKey publicKey = JWKSet.parse(jwksResponse.getBody())
                    .getKeyByKeyId(token.getHeader().getKeyID())
                    .toRSAKey();
            Assertions.assertTrue(token.verify(new RSASSAVerifier(publicKey)));
            return token;
        } catch (Exception ex) {
            throw new AssertionError("Failed to verify issued access token", ex);
        }
    }

    private Object claim(SignedJWT token, String name) {
        try {
            return token.getJWTClaimsSet().getClaim(name);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private String clientAssertion() {
        try {
            Instant now = Instant.now();
            SignedJWT assertion = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(CLIENT_KEY.getKeyID())
                            .build(),
                    new JWTClaimsSet.Builder()
                            .issuer("ragent")
                            .subject("ragent")
                            .audience("http://localhost:9200/oauth2/token")
                            .issueTime(Date.from(now))
                            .expirationTime(Date.from(now.plusSeconds(60)))
                            .jwtID(UUID.randomUUID().toString())
                            .build()
            );
            assertion.sign(new RSASSASigner(CLIENT_KEY));
            return assertion.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static RSAKey generateClientKey() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .generate();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static HttpServer startClientJwksServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] response = new JWKSet(CLIENT_KEY.toPublicJWK())
                        .toString()
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SubjectVerifierTestConfig {

        @Bean
        @Primary
        SaTokenSubjectVerifier testSubjectVerifier() {
            return new SaTokenSubjectVerifier(null) {
                @Override
                public Optional<AuthUser> verify(String subjectToken) {
                    if (!"valid-sa-token".equals(subjectToken)) {
                        return Optional.empty();
                    }
                    return Optional.of(new AuthUser("user-1", "alice", "admin"));
                }
            };
        }

        @Bean
        @Primary
        ClientAssertionReplayStore testClientAssertionReplayStore() {
            Set<String> usedAssertions = ConcurrentHashMap.newKeySet();
            return (clientId, tokenId, expiresAt) ->
                    usedAssertions.add(clientId + ":" + tokenId);
        }
    }
}
