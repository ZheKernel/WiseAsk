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

import com.nimbusds.jwt.JWTParser;

final class McpJwtLogSupport {

    private static final String UNKNOWN_TOKEN_ID = "unknown";

    private McpJwtLogSupport() {
    }

    static String tokenId(String token) {
        if (token == null || token.isBlank()) {
            return UNKNOWN_TOKEN_ID;
        }
        try {
            String tokenId = JWTParser.parse(token).getJWTClaimsSet().getJWTID();
            return tokenId == null || tokenId.isBlank() ? UNKNOWN_TOKEN_ID : tokenId;
        } catch (Exception ignored) {
            return UNKNOWN_TOKEN_ID;
        }
    }
}
