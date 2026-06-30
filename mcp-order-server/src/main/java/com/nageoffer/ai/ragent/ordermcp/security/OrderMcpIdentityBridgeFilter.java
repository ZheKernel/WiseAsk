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

package com.nageoffer.ai.ragent.ordermcp.security;

import com.nageoffer.ai.ragent.mcpauth.McpCallerIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public class OrderMcpIdentityBridgeFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/mcp".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            McpCallerIdentity identity = new McpCallerIdentity(
                    jwtAuthentication.getToken().getSubject(),
                    jwtAuthentication.getToken().getClaimAsString("username"),
                    jwtAuthentication.getToken().getClaimAsString("role"),
                    scopes(jwtAuthentication),
                    clientId(jwtAuthentication)
            );
            request.setAttribute(OrderMcpIdentityContext.REQUEST_ATTRIBUTE, identity);
        }
        filterChain.doFilter(request, response);
    }

    private Set<String> scopes(JwtAuthenticationToken authentication) {
        Object claim = authentication.getToken().getClaims().get("scope");
        if (claim instanceof String value) {
            return Arrays.stream(value.trim().split("\\s+"))
                    .filter(scope -> !scope.isBlank())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (claim instanceof Collection<?> values) {
            Set<String> scopes = new LinkedHashSet<>();
            values.forEach(value -> scopes.add(String.valueOf(value)));
            return Set.copyOf(scopes);
        }
        return Set.of();
    }

    private String clientId(JwtAuthenticationToken authentication) {
        String clientId = authentication.getToken().getClaimAsString("client_id");
        return clientId != null ? clientId : authentication.getToken().getClaimAsString("azp");
    }
}
