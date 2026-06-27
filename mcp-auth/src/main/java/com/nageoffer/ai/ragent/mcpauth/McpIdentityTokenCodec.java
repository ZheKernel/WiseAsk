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

package com.nageoffer.ai.ragent.mcpauth;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues and verifies short-lived HMAC JWTs for internal MCP calls.
 */
public class McpIdentityTokenCodec {

    private static final String ALGORITHM = "HS256";
    private static final long DEFAULT_CLOCK_SKEW_SECONDS = 30L;

    private final byte[] secret;
    private final String issuer;
    private final Duration ttl;
    private final Clock clock;
    private final long clockSkewSeconds;

    public McpIdentityTokenCodec(String secret, String issuer, Duration ttl) {
        this(secret, issuer, ttl, Clock.systemUTC(), DEFAULT_CLOCK_SKEW_SECONDS);
    }

    McpIdentityTokenCodec(String secret, String issuer, Duration ttl, Clock clock, long clockSkewSeconds) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("MCP identity secret must contain at least 32 bytes");
        }
        if (isBlank(issuer)) {
            throw new IllegalArgumentException("MCP identity issuer must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("MCP identity token TTL must be positive");
        }
        if (clockSkewSeconds < 0) {
            throw new IllegalArgumentException("MCP identity clock skew must not be negative");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.clockSkewSeconds = clockSkewSeconds;
    }

    public String issue(McpCallerIdentity identity, String audience) {
        validateIdentity(identity);
        if (isBlank(audience)) {
            throw new IllegalArgumentException("MCP identity audience must not be blank");
        }

        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", identity.userId());
        payload.put("username", identity.username());
        payload.put("role", identity.role());
        payload.put("iss", issuer);
        payload.put("aud", audience);
        payload.put("iat", Date.from(now));
        payload.put("nbf", Date.from(now));
        payload.put("exp", Date.from(now.plus(ttl)));
        payload.put("jti", UUID.randomUUID().toString());
        return JWTUtil.createToken(payload, secret);
    }

    public McpCallerIdentity verify(String token, String expectedAudience) {
        if (isBlank(token)) {
            throw new McpIdentityAuthenticationException("Missing MCP identity token");
        }
        if (isBlank(expectedAudience)) {
            throw new IllegalArgumentException("Expected MCP identity audience must not be blank");
        }

        try {
            JWT jwt = JWTUtil.parseToken(token);
            if (!ALGORITHM.equalsIgnoreCase(jwt.getAlgorithm())) {
                throw new McpIdentityAuthenticationException("Unsupported MCP identity token algorithm");
            }
            if (!jwt.setKey(secret).verify()) {
                throw new McpIdentityAuthenticationException("Invalid MCP identity token signature");
            }
            JWTValidator.of(jwt).validateDate(Date.from(clock.instant()), clockSkewSeconds);

            String tokenIssuer = stringClaim(jwt, "iss");
            String audience = stringClaim(jwt, "aud");
            if (!issuer.equals(tokenIssuer)) {
                throw new McpIdentityAuthenticationException("Invalid MCP identity token issuer");
            }
            if (!expectedAudience.equals(audience)) {
                throw new McpIdentityAuthenticationException("Invalid MCP identity token audience");
            }

            McpCallerIdentity identity = new McpCallerIdentity(
                    stringClaim(jwt, "sub"),
                    stringClaim(jwt, "username"),
                    stringClaim(jwt, "role")
            );
            validateIdentity(identity);
            return identity;
        } catch (McpIdentityAuthenticationException ex) {
            throw ex;
        } catch (ValidateException ex) {
            throw new McpIdentityAuthenticationException("Expired or inactive MCP identity token", ex);
        } catch (Exception ex) {
            throw new McpIdentityAuthenticationException("Malformed MCP identity token", ex);
        }
    }

    private String stringClaim(JWT jwt, String name) {
        Object value = jwt.getPayload(name);
        return value == null ? null : String.valueOf(value);
    }

    private void validateIdentity(McpCallerIdentity identity) {
        if (identity == null || isBlank(identity.userId()) || isBlank(identity.role())) {
            throw new McpIdentityAuthenticationException("Incomplete MCP caller identity");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
