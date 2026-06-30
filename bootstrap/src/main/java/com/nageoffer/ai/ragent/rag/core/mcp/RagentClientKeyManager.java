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
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;
import java.util.UUID;

/**
 * Owns Ragent's OAuth client private key. Local development uses an ephemeral key.
 */
@Slf4j
@Component
public class RagentClientKeyManager {

    private final RSAKey rsaKey;

    public RagentClientKeyManager(McpAuthorizationProperties properties) {
        this.rsaKey = loadOrGenerate(properties);
    }

    public RSAKey privateKey() {
        return rsaKey;
    }

    public Map<String, Object> publicJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }

    private RSAKey loadOrGenerate(McpAuthorizationProperties properties) {
        if (properties.getKeyStorePath() == null || properties.getKeyStorePath().isBlank()) {
            log.warn("Ragent OAuth client uses an ephemeral RSA key; configure a PKCS12 key for production");
            return generate();
        }
        if (properties.getKeyAlias() == null || properties.getKeyAlias().isBlank()) {
            throw new IllegalArgumentException("Ragent OAuth client key alias must not be blank");
        }
        try {
            char[] password = password(properties.getKeyStorePassword());
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(Path.of(properties.getKeyStorePath()))) {
                keyStore.load(input, password);
            }
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(properties.getKeyAlias(), password);
            Certificate certificate = keyStore.getCertificate(properties.getKeyAlias());
            if (!(privateKey instanceof RSAPrivateKey rsaPrivateKey)
                    || certificate == null
                    || !(certificate.getPublicKey() instanceof RSAPublicKey rsaPublicKey)) {
                throw new IllegalStateException("Configured Ragent key alias is not an RSA key pair");
            }
            return new RSAKey.Builder(rsaPublicKey)
                    .privateKey(rsaPrivateKey)
                    .keyID(properties.getKeyAlias())
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load Ragent OAuth client key", ex);
        }
    }

    private RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .generate();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate Ragent OAuth client key", ex);
        }
    }

    private char[] password(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }
}
