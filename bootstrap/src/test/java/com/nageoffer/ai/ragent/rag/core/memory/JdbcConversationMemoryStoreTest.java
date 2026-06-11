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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcConversationMemoryStoreTest {

    @Test
    void shouldLoadHistoryAfterLatestSummaryMessageIdWhenSummaryExists() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationMessageService messageService = mock(ConversationMessageService.class);
        ConversationGroupService groupService = mock(ConversationGroupService.class);
        JdbcConversationMemoryStore store = new JdbcConversationMemoryStore(
                conversationService,
                messageService,
                groupService,
                new MemoryProperties()
        );
        when(groupService.findLatestSummary("conv-1", "user-1")).thenReturn(ConversationSummaryDO.builder()
                .lastMessageId("100")
                .content("已压缩到第 4 轮")
                .build());
        when(groupService.listMessagesBetweenIds("conv-1", "user-1", "100", null)).thenReturn(List.of(
                messageDO("101", "user", "第 5 轮问题"),
                messageDO("102", "assistant", "第 5 轮回答")
        ));

        List<ChatMessage> history = store.loadHistory("conv-1", "user-1");

        Assertions.assertEquals(2, history.size());
        Assertions.assertEquals(ChatMessage.Role.USER, history.get(0).getRole());
        Assertions.assertEquals("第 5 轮问题", history.get(0).getContent());
        Assertions.assertEquals(ChatMessage.Role.ASSISTANT, history.get(1).getRole());
        Assertions.assertEquals("第 5 轮回答", history.get(1).getContent());
        verify(groupService).listMessagesBetweenIds("conv-1", "user-1", "100", null);
        verifyNoInteractions(messageService);
    }

    @Test
    void shouldFallbackToFixedRecentHistoryWhenNoSummaryExists() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationMessageService messageService = mock(ConversationMessageService.class);
        ConversationGroupService groupService = mock(ConversationGroupService.class);
        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setHistoryKeepTurns(4);
        JdbcConversationMemoryStore store = new JdbcConversationMemoryStore(
                conversationService,
                messageService,
                groupService,
                memoryProperties
        );
        when(groupService.findLatestSummary("conv-1", "user-1")).thenReturn(null);
        when(messageService.listMessages("conv-1", "user-1", 8, ConversationMessageOrder.DESC)).thenReturn(List.of(
                messageVO("user", "最近问题"),
                messageVO("assistant", "最近回答")
        ));

        List<ChatMessage> history = store.loadHistory("conv-1", "user-1");

        Assertions.assertEquals(2, history.size());
        Assertions.assertEquals("最近问题", history.get(0).getContent());
        Assertions.assertEquals("最近回答", history.get(1).getContent());
        verify(messageService).listMessages("conv-1", "user-1", 8, ConversationMessageOrder.DESC);
        verify(groupService, never()).listMessagesBetweenIds(any(), any(), any(), any());
    }

    private ConversationMessageDO messageDO(String id, String role, String content) {
        return ConversationMessageDO.builder()
                .id(id)
                .role(role)
                .content(content)
                .build();
    }

    private ConversationMessageVO messageVO(String role, String content) {
        return ConversationMessageVO.builder()
                .role(role)
                .content(content)
                .build();
    }
}
