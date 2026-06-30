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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class McpAccessTokenCache {

    private final McpAuthorizationProperties properties;
    private final ConcurrentHashMap<String, McpAccessToken> tokens = new ConcurrentHashMap<>();

    public McpAccessToken getOrLoad(String cacheKey, Supplier<McpAccessToken> loader) {
        return getOrLoad(cacheKey, "unspecified", loader);
    }

    public McpAccessToken getOrLoad(
            String cacheKey,
            String cacheContext,
            Supplier<McpAccessToken> loader) {
        McpAccessToken current = tokens.get(cacheKey);
        if (usable(current)) {
            log.info("[MCP-AUTH][TOKEN_CACHE_HIT] credential reused, context={}, expiresAt={}",
                    cacheContext, current.expiresAt());
            return current;
        }
        return tokens.compute(cacheKey, (key, existing) -> {
            if (usable(existing)) {
                log.info("[MCP-AUTH][TOKEN_CACHE_HIT] credential reused after concurrent refresh, "
                                + "context={}, expiresAt={}",
                        cacheContext, existing.expiresAt());
                return existing;
            }
            log.info("[MCP-AUTH][TOKEN_CACHE_MISS] requesting credential from Auth Server, context={}",
                    cacheContext);
            return loader.get();
        });
    }

    public void evict(String cacheKey) {
        tokens.remove(cacheKey);
        log.info("[MCP-AUTH][TOKEN_CACHE_EVICT] cached credential removed");
    }

    private boolean usable(McpAccessToken token) {
        return token != null && token.isUsable(Instant.now(), properties.getCacheSkewSeconds());
    }
}
