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
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PrivateKeyJwtClientAssertionService {

    private final McpAuthorizationProperties properties;
    private final RagentClientKeyManager keyManager;

    public String create() {
        try {
            Instant now = Instant.now();
            SignedJWT assertion = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(keyManager.privateKey().getKeyID())
                            .build(),
                    new JWTClaimsSet.Builder()
                            .issuer(properties.getClientId())
                            .subject(properties.getClientId())
                            .audience(properties.getTokenUri())
                            .issueTime(Date.from(now))
                            .expirationTime(Date.from(now.plusSeconds(
                                    properties.getClientAssertionTtlSeconds()
                            )))
                            .jwtID(UUID.randomUUID().toString())
                            .build()
            );
            assertion.sign(new RSASSASigner(keyManager.privateKey()));
            return assertion.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign OAuth client assertion", ex);
        }
    }
}
