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

import com.alibaba.ttl.TransmittableThreadLocal;
import org.springframework.stereotype.Component;

/**
 * Carries the current Sa-Token without adding it to normal user data objects.
 */
@Component
public class McpSubjectTokenContext {

    private final TransmittableThreadLocal<String> tokenHolder = new TransmittableThreadLocal<>();

    public void set(String token) {
        if (token == null || token.isBlank()) {
            tokenHolder.remove();
            return;
        }
        tokenHolder.set(token);
    }

    public String requireToken() {
        String token = tokenHolder.get();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Current user session token is unavailable");
        }
        return token;
    }

    public void clear() {
        tokenHolder.remove();
    }
}
