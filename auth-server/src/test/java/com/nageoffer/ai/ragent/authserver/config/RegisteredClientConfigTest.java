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

package com.nageoffer.ai.ragent.authserver.config;

import com.nageoffer.ai.ragent.authserver.security.McpScopes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.List;

class RegisteredClientConfigTest {

    @Test
    void shouldRegisterMultiplePrivateKeyJwtClients() {
        AuthServerProperties properties = new AuthServerProperties();
        properties.setClients(List.of(
                client("ragent", "http://localhost:9090/jwks"),
                client("reporting-agent", "http://localhost:9300/jwks")
        ));

        RegisteredClientRepository repository =
                new RegisteredClientConfig().registeredClientRepository(properties);

        RegisteredClient ragent = repository.findByClientId("ragent");
        RegisteredClient reporting = repository.findByClientId("reporting-agent");
        Assertions.assertNotNull(ragent);
        Assertions.assertNotNull(reporting);
        Assertions.assertTrue(ragent.getClientAuthenticationMethods()
                .contains(ClientAuthenticationMethod.PRIVATE_KEY_JWT));
        Assertions.assertEquals(
                "http://localhost:9090/jwks",
                ragent.getClientSettings().getJwkSetUrl()
        );
    }

    private AuthServerProperties.Client client(String clientId, String jwkSetUri) {
        AuthServerProperties.Client client = new AuthServerProperties.Client();
        client.setClientId(clientId);
        client.setJwkSetUri(jwkSetUri);
        client.setScopes(List.of(
                McpScopes.DISCOVER,
                McpScopes.ORDER_READ_SELF,
                McpScopes.ORDER_READ_ANY
        ));
        return client;
    }
}
