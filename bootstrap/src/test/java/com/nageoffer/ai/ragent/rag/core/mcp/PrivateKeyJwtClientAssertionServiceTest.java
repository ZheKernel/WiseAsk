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
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PrivateKeyJwtClientAssertionServiceTest {

    @Test
    void shouldSignShortLivedClientAssertionWithRagentPrivateKey() throws Exception {
        McpAuthorizationProperties properties = new McpAuthorizationProperties();
        properties.setClientId("ragent");
        properties.setTokenUri("http://localhost:9200/oauth2/token");
        properties.setClientAssertionTtlSeconds(60);
        RagentClientKeyManager keyManager = new RagentClientKeyManager(properties);
        PrivateKeyJwtClientAssertionService service =
                new PrivateKeyJwtClientAssertionService(properties, keyManager);

        SignedJWT assertion = SignedJWT.parse(service.create());

        Assertions.assertEquals(JWSAlgorithm.RS256, assertion.getHeader().getAlgorithm());
        Assertions.assertEquals(keyManager.privateKey().getKeyID(), assertion.getHeader().getKeyID());
        Assertions.assertTrue(assertion.verify(new RSASSAVerifier(
                keyManager.privateKey().toRSAPublicKey()
        )));
        Assertions.assertEquals("ragent", assertion.getJWTClaimsSet().getIssuer());
        Assertions.assertEquals("ragent", assertion.getJWTClaimsSet().getSubject());
        Assertions.assertEquals(
                "http://localhost:9200/oauth2/token",
                assertion.getJWTClaimsSet().getAudience().get(0)
        );
        long lifetime = assertion.getJWTClaimsSet().getExpirationTime().getTime()
                - assertion.getJWTClaimsSet().getIssueTime().getTime();
        Assertions.assertEquals(60_000L, lifetime);
        Assertions.assertNotNull(assertion.getJWTClaimsSet().getJWTID());
    }
}
