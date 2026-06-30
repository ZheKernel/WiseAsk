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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class OrderMcpJwtDecoderTest {

    private RSAKey rsaKey;
    private HttpServer jwksServer;
    private String jwkSetUri;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048)
                .keyID(UUID.randomUUID().toString())
                .generate();
        jwksServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        jwksServer.createContext("/jwks", exchange -> {
            byte[] response = new JWKSet(rsaKey.toPublicJWK())
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        jwksServer.start();
        jwkSetUri = "http://localhost:" + jwksServer.getAddress().getPort() + "/jwks";
    }

    @AfterEach
    void tearDown() {
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
    }

    @Test
    void shouldAcceptValidTokenAndRejectWrongAudience() {
        JwtDecoder decoder = decoder();

        Assertions.assertEquals(
                "user-1",
                decoder.decode(token(
                        rsaKey,
                        "http://localhost:9200",
                        List.of("order-mcp"),
                        Instant.now().plusSeconds(300)
                )).getSubject()
        );
        Assertions.assertThrows(
                JwtValidationException.class,
                () -> decoder.decode(token(
                        rsaKey,
                        "http://localhost:9200",
                        List.of("another-service"),
                        Instant.now().plusSeconds(300)
                ))
        );
    }

    @Test
    void shouldRejectWrongIssuerExpiredTokenAndWrongSignature() throws Exception {
        JwtDecoder decoder = decoder();

        Assertions.assertThrows(
                JwtValidationException.class,
                () -> decoder.decode(token(
                        rsaKey,
                        "http://untrusted-issuer",
                        List.of("order-mcp"),
                        Instant.now().plusSeconds(300)
                ))
        );
        Assertions.assertThrows(
                JwtValidationException.class,
                () -> decoder.decode(token(
                        rsaKey,
                        "http://localhost:9200",
                        List.of("order-mcp"),
                        Instant.now().minusSeconds(60)
                ))
        );
        RSAKey attackerKey = new RSAKeyGenerator(2048)
                .keyID(rsaKey.getKeyID())
                .generate();
        Assertions.assertThrows(
                JwtException.class,
                () -> decoder.decode(token(
                        attackerKey,
                        "http://localhost:9200",
                        List.of("order-mcp"),
                        Instant.now().plusSeconds(300)
                ))
        );
    }

    @Test
    void shouldRejectHs256Downgrade() {
        Assertions.assertThrows(
                JwtException.class,
                () -> decoder().decode(hs256Token())
        );
    }

    private JwtDecoder decoder() {
        OrderMcpAuthProperties properties = new OrderMcpAuthProperties();
        properties.setIssuer("http://localhost:9200");
        properties.setAudience("order-mcp");
        properties.setJwkSetUri(jwkSetUri);
        return new OrderMcpSecurityConfig().orderMcpJwtDecoder(properties);
    }

    private String token(
            RSAKey signingKey,
            String issuer,
            List<String> audiences,
            Instant expiresAt) {
        Instant now = Instant.now();
        Instant issuedAt = expiresAt.isAfter(now) ? now : expiresAt.minusSeconds(300);
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey))
        );
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(signingKey.getKeyID())
                .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("user-1")
                .audience(audiences)
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .claim("scope", "order:read:self")
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String hs256Token() {
        try {
            Instant now = Instant.now();
            SignedJWT token = new SignedJWT(
                    new JWSHeader(JWSAlgorithm.HS256),
                    new JWTClaimsSet.Builder()
                            .issuer("http://localhost:9200")
                            .subject("user-1")
                            .audience("order-mcp")
                            .issueTime(java.util.Date.from(now))
                            .expirationTime(java.util.Date.from(now.plusSeconds(300)))
                            .build()
            );
            token.sign(new MACSigner("01234567890123456789012345678901"));
            return token.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
