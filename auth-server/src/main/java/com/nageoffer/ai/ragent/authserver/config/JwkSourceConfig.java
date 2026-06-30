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

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

@Slf4j
@Configuration
public class JwkSourceConfig {

    @Bean
    public RSAKey authServerRsaKey(AuthServerProperties properties) {
        if (properties.getKeyStorePath() != null && !properties.getKeyStorePath().isBlank()) {
            return load(properties);
        }
        log.warn("Auth Server uses an ephemeral RSA signing key; configure a PKCS12 key for production");
        return generate();
    }

    private RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .generate();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate Auth Server RSA key", ex);
        }
    }

    private RSAKey load(AuthServerProperties properties) {
        if (properties.getKeyAlias() == null || properties.getKeyAlias().isBlank()) {
            throw new IllegalArgumentException("Auth Server signing key alias must not be blank");
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
                throw new IllegalStateException("Configured Auth Server alias is not an RSA key pair");
            }
            return new RSAKey.Builder(rsaPublicKey)
                    .privateKey(rsaPrivateKey)
                    .keyID(properties.getKeyAlias())
                    .build();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load Auth Server signing key", ex);
        }
    }

    private char[] password(String value) {
        return value == null ? new char[0] : value.toCharArray();
    }

    @Bean
    public JWKSource<SecurityContext> authServerJwkSource(RSAKey authServerRsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(authServerRsaKey));
    }

    @Bean
    public JwtDecoder authServerJwtDecoder(JWKSource<SecurityContext> authServerJwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(authServerJwkSource);
    }
}
