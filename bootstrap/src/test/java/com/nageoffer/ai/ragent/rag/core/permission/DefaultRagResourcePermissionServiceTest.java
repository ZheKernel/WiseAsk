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

package com.nageoffer.ai.ragent.rag.core.permission;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.McpToolPermissionProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultRagResourcePermissionServiceTest {

    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    private final McpToolPermissionProperties mcpProperties = new McpToolPermissionProperties();
    private final DefaultRagResourcePermissionService service =
            new DefaultRagResourcePermissionService(knowledgeBaseMapper, mcpProperties);

    @Test
    void shouldReturnGlobalAndOwnPersonalCollectionsForOrdinaryChat() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                kb("global", "global_collection", "GLOBAL", "admin-1"),
                kb("own", "own_collection", "PERSONAL", "user-1"),
                kb("other", "other_collection", "PERSONAL", "user-2")
        ));

        List<String> result = service.listRetrievableCollections(loginUser("user-1", "user"));

        Assertions.assertEquals(2, result.size());
        Assertions.assertTrue(result.contains("global_collection"));
        Assertions.assertTrue(result.contains("own_collection"));
        Assertions.assertFalse(result.contains("other_collection"));
    }

    @Test
    void shouldNotReturnOtherUsersPersonalCollectionsForAdminChat() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                kb("global", "global_collection", "GLOBAL", "admin-1"),
                kb("own", "own_admin_collection", "PERSONAL", "admin-1"),
                kb("other", "other_collection", "PERSONAL", "user-2")
        ));

        List<String> result = service.listRetrievableCollections(loginUser("admin-1", "admin"));

        Assertions.assertEquals(2, result.size());
        Assertions.assertTrue(result.contains("global_collection"));
        Assertions.assertTrue(result.contains("own_admin_collection"));
        Assertions.assertFalse(result.contains("other_collection"));
    }

    @Test
    void shouldDenyUserManagingAnotherUsersPersonalKnowledgeBase() {
        KnowledgeBaseDO kb = kb("other", "other_collection", "PERSONAL", "user-2");

        boolean result = service.canManageKnowledgeBase(loginUser("user-1", "user"), kb);

        Assertions.assertFalse(result);
    }

    @Test
    void shouldAllowAdminManagingAnyKnowledgeBase() {
        KnowledgeBaseDO kb = kb("other", "other_collection", "PERSONAL", "user-2");

        boolean result = service.canManageKnowledgeBase(loginUser("admin-1", "admin"), kb);

        Assertions.assertTrue(result);
    }

    @Test
    void shouldDenyAdminOnlyMcpToolForNormalUser() {
        mcpProperties.setAdminOnlyToolIds(List.of("admin-tool"));

        boolean result = service.canCallMcpTool(loginUser("user-1", "user"), "admin-tool");

        Assertions.assertFalse(result);
    }

    private KnowledgeBaseDO kb(String id, String collectionName, String scope, String ownerUserId) {
        return KnowledgeBaseDO.builder()
                .id(id)
                .name(id)
                .collectionName(collectionName)
                .scope(scope)
                .ownerUserId(ownerUserId)
                .deleted(0)
                .build();
    }

    private LoginUser loginUser(String userId, String role) {
        return LoginUser.builder()
                .userId(userId)
                .username(userId)
                .role(role)
                .build();
    }
}
