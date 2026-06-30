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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "auth-server")
public class AuthServerProperties {

    private String issuer = "http://localhost:9200";

    private String orderAudience = "order-mcp";

    private long accessTokenTtlSeconds = 300L;

    private String keyStorePath;

    private String keyStorePassword;

    private String keyAlias;

    private List<Client> clients = new ArrayList<>();

    @Data
    public static class Client {

        private String clientId;

        private String clientName;

        private String jwkSetUri;

        private List<String> scopes = new ArrayList<>();
    }
}
