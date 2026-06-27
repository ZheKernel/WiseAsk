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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.enums.DocumentStatus;
import com.nageoffer.ai.ragent.rag.core.permission.RagResourcePermissionService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KnowledgeChunkServiceImplTest {

    private final KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final TokenCounterService tokenCounterService = mock(TokenCounterService.class);
    private final VectorStoreService vectorStoreService = mock(VectorStoreService.class);
    private final TransactionOperations transactionOperations = mock(TransactionOperations.class);
    private final RagResourcePermissionService permissionService = mock(RagResourcePermissionService.class);
    private final KnowledgeChunkServiceImpl service = new KnowledgeChunkServiceImpl(
            chunkMapper,
            documentMapper,
            knowledgeBaseMapper,
            embeddingService,
            tokenCounterService,
            vectorStoreService,
            transactionOperations,
            permissionService
    );

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldDenyReadingChunksFromInvisibleKnowledgeBase() {
        UserContext.set(loginUser("user-1", "user"));
        KnowledgeDocumentDO document = document("doc-1", "kb-1");
        KnowledgeBaseDO knowledgeBase = knowledgeBase("kb-1", "user-2");
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(knowledgeBase);
        when(permissionService.canViewKnowledgeBase(any(LoginUser.class), same(knowledgeBase))).thenReturn(false);

        Assertions.assertThrows(
                ClientException.class,
                () -> service.pageQuery("doc-1", new KnowledgeChunkPageRequest())
        );

        verifyNoInteractions(chunkMapper);
    }

    @Test
    void shouldDenyCreatingChunkInUnmanageableKnowledgeBase() {
        UserContext.set(loginUser("user-1", "user"));
        KnowledgeDocumentDO document = document("doc-1", "kb-1");
        KnowledgeBaseDO knowledgeBase = knowledgeBase("kb-1", "user-2");
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(knowledgeBase);
        when(permissionService.canManageKnowledgeBase(any(LoginUser.class), same(knowledgeBase))).thenReturn(false);
        KnowledgeChunkCreateRequest request = new KnowledgeChunkCreateRequest();
        request.setContent("private content");

        Assertions.assertThrows(ClientException.class, () -> service.create("doc-1", request));

        verifyNoInteractions(chunkMapper, embeddingService, tokenCounterService, vectorStoreService);
    }

    private KnowledgeDocumentDO document(String id, String kbId) {
        return KnowledgeDocumentDO.builder()
                .id(id)
                .kbId(kbId)
                .enabled(1)
                .status(DocumentStatus.SUCCESS.getCode())
                .deleted(0)
                .build();
    }

    private KnowledgeBaseDO knowledgeBase(String id, String ownerUserId) {
        return KnowledgeBaseDO.builder()
                .id(id)
                .ownerUserId(ownerUserId)
                .scope("PERSONAL")
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
