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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Adapts the MCP client to Auth Server issued access tokens.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class McpIdentityTokenService {

    private final McpSubjectTokenContext subjectTokenContext;
    private final McpAccessTokenProvider accessTokenProvider;

    public String issue(LoginUser user, String audience) {
        if (user == null) {
            throw new IllegalArgumentException("MCP caller must not be null");
        }
        log.info("[MCP-AUTH][TOKEN_RESOLVE] resolving delegated MCP credential, userId={}, "
                        + "role={}, audience={}",
                user.getUserId(), user.getRole(), audience);
        return accessTokenProvider
                .userToken(subjectTokenContext.requireToken(), audience, user.getRole())
                .value();
    }

    public String issueServiceIdentity(String audience) {
        log.info("[MCP-AUTH][TOKEN_RESOLVE] resolving service MCP credential, audience={}",
                audience);
        return accessTokenProvider.serviceToken(audience).value();
    }

    public void invalidate(LoginUser user, String audience) {
        log.warn("[MCP-AUTH][TOKEN_INVALIDATE] invalidating delegated MCP credential after "
                        + "resource-server rejection, userId={}, role={}, audience={}",
                user.getUserId(), user.getRole(), audience);
        accessTokenProvider.invalidateUserToken(
                subjectTokenContext.requireToken(),
                audience,
                user.getRole()
        );
    }
}
