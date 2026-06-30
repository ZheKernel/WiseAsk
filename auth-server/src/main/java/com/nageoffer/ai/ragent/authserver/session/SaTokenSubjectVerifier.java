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

package com.nageoffer.ai.ragent.authserver.session;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.authserver.user.AuthUser;
import com.nageoffer.ai.ragent.authserver.user.AuthUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SaTokenSubjectVerifier {

    private final AuthUserRepository userRepository;

    public Optional<AuthUser> verify(String subjectToken) {
        if (subjectToken == null || subjectToken.isBlank()) {
            log.warn("[MCP-AUTH][SUBJECT_REJECTED] missing Sa-Token subject credential");
            return Optional.empty();
        }
        Object loginId = StpUtil.getLoginIdByToken(subjectToken);
        if (loginId == null) {
            log.warn("[MCP-AUTH][SUBJECT_REJECTED] Sa-Token session is invalid or expired");
            return Optional.empty();
        }
        Optional<AuthUser> user = userRepository.findActiveById(String.valueOf(loginId));
        if (user.isPresent()) {
            AuthUser verified = user.get();
            log.info("[MCP-AUTH][SUBJECT_VERIFIED] Sa-Token session and active user verified, "
                            + "userId={}, username={}, role={}",
                    verified.userId(), verified.username(), verified.role());
        } else {
            log.warn("[MCP-AUTH][SUBJECT_REJECTED] session user is missing or disabled, userId={}",
                    loginId);
        }
        return user;
    }
}
