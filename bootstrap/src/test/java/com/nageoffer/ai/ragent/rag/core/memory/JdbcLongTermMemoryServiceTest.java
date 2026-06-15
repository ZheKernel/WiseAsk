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

package com.nageoffer.ai.ragent.rag.core.memory;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.UserLongTermMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.UserLongTermMemoryMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcLongTermMemoryServiceTest {

    @Test
    void shouldRecallRelevantMemoriesInRankOrder() {
        UserLongTermMemoryMapper memoryMapper = mock(UserLongTermMemoryMapper.class);
        JdbcLongTermMemoryService service = newService(memoryMapper, mock(ConversationMessageMapper.class),
                mock(LLMService.class), mock(PromptTemplateLoader.class));
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory("1", "PREFERENCE", "preferred_backend_stack",
                        "User prefers Java and Spring implementations.", 5, 2, 0),
                memory("2", "PROJECT", "interview_project",
                        "User is preparing RAG interview talking points.", 5, 5, 0),
                memory("3", "FACT", "frontend_stack",
                        "User once mentioned a React frontend.", 3, 5, 0)
        ));

        String result = service.recall("user-1", "How should we implement Java memory cache?");

        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.contains("<long-term-memory>"));
        Assertions.assertTrue(result.indexOf("User prefers Java and Spring implementations.")
                < result.indexOf("User is preparing RAG interview talking points."));
        ArgumentCaptor<UserLongTermMemoryDO> updateCaptor = ArgumentCaptor.forClass(UserLongTermMemoryDO.class);
        verify(memoryMapper, org.mockito.Mockito.times(2)).updateById(updateCaptor.capture());
        Assertions.assertEquals(1, updateCaptor.getAllValues().get(0).getAccessCount());
    }

    @Test
    void shouldIgnoreInvalidExtractionJson() {
        UserLongTermMemoryMapper memoryMapper = mock(UserLongTermMemoryMapper.class);
        ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        JdbcLongTermMemoryService service = newService(memoryMapper, messageMapper, llmService, promptTemplateLoader);
        when(messageMapper.selectList(any())).thenReturn(recentMessages());
        when(promptTemplateLoader.render(eq("prompt/long-term-memory-extract.st"), anyMap()))
                .thenReturn("extract memories");
        when(llmService.chat(any(ChatRequest.class))).thenReturn("not json");

        service.extractAsync("conv-1", "user-1", "2");

        verify(memoryMapper, never()).insert(any(UserLongTermMemoryDO.class));
        verify(memoryMapper, never()).updateById(any(UserLongTermMemoryDO.class));
    }

    @Test
    void shouldMergeDuplicateMemoryByTypeAndKey() {
        UserLongTermMemoryMapper memoryMapper = mock(UserLongTermMemoryMapper.class);
        ConversationMessageMapper messageMapper = mock(ConversationMessageMapper.class);
        LLMService llmService = mock(LLMService.class);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        JdbcLongTermMemoryService service = newService(memoryMapper, messageMapper, llmService, promptTemplateLoader);
        when(messageMapper.selectList(any())).thenReturn(recentMessages());
        when(promptTemplateLoader.render(eq("prompt/long-term-memory-extract.st"), anyMap()))
                .thenReturn("extract memories");
        when(llmService.chat(any(ChatRequest.class))).thenReturn("""
                {
                  "memories": [
                    {
                      "type": "PREFERENCE",
                      "key": "preferred_backend_stack",
                      "content": "User strongly prefers Java/Spring implementations for backend work.",
                      "confidence": 5,
                      "importance": 4
                    }
                  ]
                }
                """);
        when(memoryMapper.selectOne(any())).thenReturn(UserLongTermMemoryDO.builder()
                .id("memory-1")
                .userId("user-1")
                .memoryType("PREFERENCE")
                .memoryKey("preferred_backend_stack")
                .content("User prefers Java.")
                .confidence(3)
                .importance(2)
                .accessCount(7)
                .status("ACTIVE")
                .build());

        service.extractAsync("conv-1", "user-1", "2");

        verify(memoryMapper, never()).insert(any(UserLongTermMemoryDO.class));
        ArgumentCaptor<UserLongTermMemoryDO> updateCaptor = ArgumentCaptor.forClass(UserLongTermMemoryDO.class);
        verify(memoryMapper).updateById(updateCaptor.capture());
        UserLongTermMemoryDO update = updateCaptor.getValue();
        Assertions.assertEquals("memory-1", update.getId());
        Assertions.assertEquals("User strongly prefers Java/Spring implementations for backend work.",
                update.getContent());
        Assertions.assertEquals(5, update.getConfidence());
        Assertions.assertEquals(4, update.getImportance());
        Assertions.assertEquals("conv-1", update.getSourceConversationId());
        Assertions.assertEquals("2", update.getSourceMessageId());
    }

    private JdbcLongTermMemoryService newService(UserLongTermMemoryMapper memoryMapper,
                                                ConversationMessageMapper messageMapper,
                                                LLMService llmService,
                                                PromptTemplateLoader promptTemplateLoader) {
        MemoryProperties properties = new MemoryProperties();
        properties.setLongTermEnabled(true);
        properties.setLongTermExtractionEnabled(true);
        properties.setLongTermRecallLimit(2);
        properties.setLongTermExtractionRecentMessages(4);
        properties.setLongTermMaxContentLength(300);
        return new JdbcLongTermMemoryService(
                memoryMapper,
                messageMapper,
                properties,
                llmService,
                promptTemplateLoader,
                Runnable::run
        );
    }

    private UserLongTermMemoryDO memory(String id,
                                        String type,
                                        String key,
                                        String content,
                                        int confidence,
                                        int importance,
                                        int accessCount) {
        return UserLongTermMemoryDO.builder()
                .id(id)
                .userId("user-1")
                .memoryType(type)
                .memoryKey(key)
                .content(content)
                .confidence(confidence)
                .importance(importance)
                .accessCount(accessCount)
                .status("ACTIVE")
                .build();
    }

    private List<ConversationMessageDO> recentMessages() {
        return List.of(
                ConversationMessageDO.builder()
                        .id("1")
                        .conversationId("conv-1")
                        .userId("user-1")
                        .role("user")
                        .content("I prefer Java/Spring implementations.")
                        .build(),
                ConversationMessageDO.builder()
                        .id("2")
                        .conversationId("conv-1")
                        .userId("user-1")
                        .role("assistant")
                        .content("Understood.")
                        .build()
        );
    }
}
