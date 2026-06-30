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

import com.nageoffer.ai.ragent.rag.config.McpAuthorizationProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

class McpAccessTokenCacheTest {

    @Test
    void shouldReuseUsableTokenAndReloadNearExpiry() {
        McpAuthorizationProperties properties = new McpAuthorizationProperties();
        properties.setCacheSkewSeconds(30);
        McpAccessTokenCache cache = new McpAccessTokenCache(properties);
        AtomicInteger loads = new AtomicInteger();

        McpAccessToken first = cache.getOrLoad("key", () -> {
            loads.incrementAndGet();
            return new McpAccessToken(
                    "first",
                    Instant.now().plusSeconds(300),
                    Set.of("order:read:self")
            );
        });
        McpAccessToken reused = cache.getOrLoad("key", () -> {
            loads.incrementAndGet();
            return new McpAccessToken(
                    "unexpected",
                    Instant.now().plusSeconds(300),
                    Set.of()
            );
        });

        Assertions.assertSame(first, reused);
        Assertions.assertEquals(1, loads.get());

        cache.evict("key");
        McpAccessToken refreshed = cache.getOrLoad("key", () -> {
            loads.incrementAndGet();
            return new McpAccessToken(
                    "second",
                    Instant.now().plusSeconds(300),
                    Set.of("order:read:self")
            );
        });
        Assertions.assertEquals("second", refreshed.value());
        Assertions.assertEquals(2, loads.get());
    }
}
