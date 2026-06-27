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

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.mcpauth.McpCallerIdentity;
import com.nageoffer.ai.ragent.mcpauth.McpIdentityTokenCodec;
import com.nageoffer.ai.ragent.rag.config.McpIdentityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Creates audience-bound identities for remote MCP requests.
 */
@Service
@RequiredArgsConstructor
public class McpIdentityTokenService {

    private static final McpCallerIdentity SERVICE_IDENTITY =
            new McpCallerIdentity("ragent-service", "ragent-service", "system");

    private final McpIdentityProperties properties;

    private volatile McpIdentityTokenCodec codec;

    public String issue(LoginUser user, String audience) {
        if (user == null) {
            throw new IllegalArgumentException("MCP caller must not be null");
        }
        return codec().issue(
                new McpCallerIdentity(user.getUserId(), user.getUsername(), user.getRole()),
                audience
        );
    }

    public String issueServiceIdentity(String audience) {
        return codec().issue(SERVICE_IDENTITY, audience);
    }

    private McpIdentityTokenCodec codec() {
        McpIdentityTokenCodec current = codec;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (codec == null) {
                codec = new McpIdentityTokenCodec(
                        properties.getSecret(),
                        properties.getIssuer(),
                        Duration.ofSeconds(properties.getTtlSeconds())
                );
            }
            return codec;
        }
    }
}
