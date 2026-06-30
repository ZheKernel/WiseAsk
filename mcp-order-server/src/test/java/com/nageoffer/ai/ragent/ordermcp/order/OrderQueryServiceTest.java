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

package com.nageoffer.ai.ragent.ordermcp.order;

import com.nageoffer.ai.ragent.mcpauth.McpCallerIdentity;
import com.nageoffer.ai.ragent.mcpauth.McpScopes;
import com.nageoffer.ai.ragent.ordermcp.security.OrderMcpAuthorizationException;
import com.nageoffer.ai.ragent.ordermcp.security.OrderMcpAuthorizationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderQueryServiceTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderQueryService service =
            new OrderQueryService(repository, new OrderMcpAuthorizationService());

    @Test
    void shouldForceNormalUserIdForListQuery() {
        McpCallerIdentity caller = user();
        when(repository.findByUserId(eq("user-1"), any())).thenReturn(List.of());

        service.listMine(caller, new OrderQueryCriteria("user-2", "paid", null, null, 500));

        ArgumentCaptor<OrderQueryCriteria> criteriaCaptor = ArgumentCaptor.forClass(OrderQueryCriteria.class);
        verify(repository).findByUserId(eq("user-1"), criteriaCaptor.capture());
        Assertions.assertNull(criteriaCaptor.getValue().userId());
        Assertions.assertEquals("PAID", criteriaCaptor.getValue().status());
        Assertions.assertEquals(100, criteriaCaptor.getValue().limit());
    }

    @Test
    void shouldHideAnotherUsersOrderFromNormalUser() {
        McpCallerIdentity caller = user();
        when(repository.findByOrderNoAndUserId("ORD-2", "user-1")).thenReturn(Optional.empty());

        Optional<OrderRecord> result = service.findDetail(caller, "ORD-2");

        Assertions.assertTrue(result.isEmpty());
        verify(repository).findByOrderNoAndUserId("ORD-2", "user-1");
        verify(repository, never()).findByOrderNo("ORD-2");
    }

    @Test
    void shouldAllowAdministratorToSearchAllOrders() {
        McpCallerIdentity caller = admin();
        when(repository.searchAll(any())).thenReturn(List.of());

        service.adminSearch(caller, new OrderQueryCriteria("user-2", null, null, null, 20));

        ArgumentCaptor<OrderQueryCriteria> criteriaCaptor = ArgumentCaptor.forClass(OrderQueryCriteria.class);
        verify(repository).searchAll(criteriaCaptor.capture());
        Assertions.assertEquals("user-2", criteriaCaptor.getValue().userId());
    }

    @Test
    void shouldAllowAdministratorToInspectAnyOrder() {
        McpCallerIdentity caller = admin();
        when(repository.findByOrderNo("ORD-2")).thenReturn(Optional.empty());

        service.findDetail(caller, "ORD-2");

        verify(repository).findByOrderNo("ORD-2");
        verify(repository, never()).findByOrderNoAndUserId(any(), any());
    }

    @Test
    void shouldRejectNormalUserAndSystemIdentityFromAdminQuery() {
        Assertions.assertThrows(OrderMcpAuthorizationException.class,
                () -> service.adminSearch(
                        new McpCallerIdentity("user-1", "alice", "user"),
                        new OrderQueryCriteria(null, null, null, null, 20)
                ));
        Assertions.assertThrows(OrderMcpAuthorizationException.class,
                () -> service.listMine(
                        new McpCallerIdentity("ragent-service", "ragent-service", "system"),
                        new OrderQueryCriteria(null, null, null, null, 20)
                ));
    }

    @Test
    void shouldRejectAdminRoleWithoutReadAnyScope() {
        McpCallerIdentity roleOnlyAdmin = new McpCallerIdentity(
                "admin-1",
                "admin",
                "admin",
                Set.of(McpScopes.ORDER_READ_SELF),
                "ragent"
        );

        Assertions.assertThrows(
                OrderMcpAuthorizationException.class,
                () -> service.adminSearch(
                        roleOnlyAdmin,
                        new OrderQueryCriteria(null, null, null, null, 20)
                )
        );
    }

    @Test
    void shouldRejectReadAnyScopeWithoutAdminRole() {
        McpCallerIdentity scopeOnlyUser = new McpCallerIdentity(
                "user-1",
                "alice",
                "user",
                Set.of(McpScopes.ORDER_READ_SELF, McpScopes.ORDER_READ_ANY),
                "ragent"
        );

        Assertions.assertThrows(
                OrderMcpAuthorizationException.class,
                () -> service.adminSearch(
                        scopeOnlyUser,
                        new OrderQueryCriteria(null, null, null, null, 20)
                )
        );
    }

    private McpCallerIdentity user() {
        return new McpCallerIdentity(
                "user-1",
                "alice",
                "user",
                Set.of(McpScopes.ORDER_READ_SELF),
                "ragent"
        );
    }

    private McpCallerIdentity admin() {
        return new McpCallerIdentity(
                "admin-1",
                "admin",
                "admin",
                Set.of(McpScopes.ORDER_READ_SELF, McpScopes.ORDER_READ_ANY),
                "ragent"
        );
    }
}
