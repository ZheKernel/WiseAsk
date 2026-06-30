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

import com.nageoffer.ai.ragent.mcpauth.McpScopes;
import com.nageoffer.ai.ragent.rag.config.McpAuthorizationProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthServerMcpAccessTokenProviderTest {

    @Test
    void shouldExchangeSubjectTokenAndCacheResponse() {
        McpAuthorizationProperties properties = new McpAuthorizationProperties();
        properties.setTokenUri("http://localhost:9200/oauth2/token");
        properties.setClientId("ragent");
        properties.setCacheSkewSeconds(30);
        PrivateKeyJwtClientAssertionService assertionService =
                mock(PrivateKeyJwtClientAssertionService.class);
        when(assertionService.create()).thenReturn("signed-client-assertion");
        McpAccessTokenCache cache = new McpAccessTokenCache(properties);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthServerMcpAccessTokenProvider provider = new AuthServerMcpAccessTokenProvider(
                properties,
                assertionService,
                cache,
                builder.build()
        );

        server.expect(requestTo("http://localhost:9200/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("client_id=ragent")))
                .andExpect(content().string(containsString(
                        "client_assertion=signed-client-assertion"
                )))
                .andExpect(content().string(containsString("subject_token=sa-session-token")))
                .andExpect(content().string(containsString("audience=order-mcp")))
                .andRespond(withSuccess(
                        """
                                {
                                  "access_token": "order-access-token",
                                  "token_type": "Bearer",
                                  "expires_in": 300,
                                  "scope": "order:read:self"
                                }
                                """,
                        MediaType.APPLICATION_JSON
                ));

        McpAccessToken first = provider.userToken("sa-session-token", "order-mcp", "user");
        McpAccessToken cached = provider.userToken("sa-session-token", "order-mcp", "user");

        Assertions.assertEquals("order-access-token", first.value());
        Assertions.assertEquals(Set.of(McpScopes.ORDER_READ_SELF), first.scopes());
        Assertions.assertSame(first, cached);
        server.verify();
    }
}
