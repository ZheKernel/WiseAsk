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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.permission.RagResourcePermissionService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceImplTest {

    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
    private final VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
    private final S3Client s3Client = mock(S3Client.class);
    private final RagResourcePermissionService permissionService = mock(RagResourcePermissionService.class);
    private final KnowledgeBaseServiceImpl service = new KnowledgeBaseServiceImpl(
            knowledgeBaseMapper,
            knowledgeDocumentMapper,
            vectorStoreAdmin,
            s3Client,
            permissionService
    );

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldCreatePersonalKnowledgeBaseForNormalUser() {
        UserContext.set(loginUser("user-1", "user"));
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);

        service.create(request("Personal KB", "user_personal"));

        ArgumentCaptor<KnowledgeBaseDO> captor = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(knowledgeBaseMapper).insert(captor.capture());
        Assertions.assertEquals("PERSONAL", captor.getValue().getScope());
        Assertions.assertEquals("user-1", captor.getValue().getOwnerUserId());
    }

    @Test
    void shouldCreateGlobalKnowledgeBaseForAdminByDefault() {
        UserContext.set(loginUser("admin-1", "admin"));
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);

        service.create(request("Global KB", "global_kb"));

        ArgumentCaptor<KnowledgeBaseDO> captor = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(knowledgeBaseMapper).insert(captor.capture());
        Assertions.assertEquals("GLOBAL", captor.getValue().getScope());
        Assertions.assertEquals("admin-1", captor.getValue().getOwnerUserId());
    }

    private KnowledgeBaseCreateRequest request(String name, String collectionName) {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName(name);
        request.setEmbeddingModel("bge");
        request.setCollectionName(collectionName);
        return request;
    }

    private LoginUser loginUser(String userId, String role) {
        return LoginUser.builder()
                .userId(userId)
                .username(userId)
                .role(role)
                .build();
    }
}
