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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

class McpIdentityTokenCodecTest {

    private static final String SECRET = "test-order-mcp-shared-secret-value-123456";
    private static final Instant NOW = Instant.parse("2026-06-27T08:00:00Z");

    @Test
    void shouldRoundTripCallerIdentity() {
        McpIdentityTokenCodec codec = codec("ragent", NOW);
        McpCallerIdentity expected = new McpCallerIdentity("user-1", "alice", "user");

        String token = codec.issue(expected, "order-mcp");

        Assertions.assertEquals(expected, codec.verify(token, "order-mcp"));
    }

    @Test
    void shouldRejectTamperedToken() {
        McpIdentityTokenCodec codec = codec("ragent", NOW);
        String token = codec.issue(new McpCallerIdentity("user-1", "alice", "user"), "order-mcp");
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");

        Assertions.assertThrows(McpIdentityAuthenticationException.class,
                () -> codec.verify(tampered, "order-mcp"));
    }

    @Test
    void shouldRejectWrongIssuerAndAudience() {
        McpIdentityTokenCodec issuerA = codec("ragent-a", NOW);
        McpIdentityTokenCodec issuerB = codec("ragent-b", NOW);
        String token = issuerA.issue(new McpCallerIdentity("admin-1", "admin", "admin"), "order-mcp");

        Assertions.assertThrows(McpIdentityAuthenticationException.class,
                () -> issuerB.verify(token, "order-mcp"));
        Assertions.assertThrows(McpIdentityAuthenticationException.class,
                () -> issuerA.verify(token, "another-mcp"));
    }

    @Test
    void shouldRejectExpiredToken() {
        McpIdentityTokenCodec issuer = codec("ragent", NOW);
        String token = issuer.issue(new McpCallerIdentity("user-1", "alice", "user"), "order-mcp");
        McpIdentityTokenCodec verifier = codec("ragent", NOW.plus(Duration.ofMinutes(10)));

        Assertions.assertThrows(McpIdentityAuthenticationException.class,
                () -> verifier.verify(token, "order-mcp"));
    }

    private McpIdentityTokenCodec codec(String issuer, Instant now) {
        return new McpIdentityTokenCodec(
                SECRET,
                issuer,
                Duration.ofMinutes(5),
                Clock.fixed(now, ZoneOffset.UTC),
                30L
        );
    }
}
