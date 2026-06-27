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
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
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
                groupService
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
    void shouldLoadAllRawHistoryWhenNoSummaryExists() {
        ConversationService conversationService = mock(ConversationService.class);
        ConversationMessageService messageService = mock(ConversationMessageService.class);
        ConversationGroupService groupService = mock(ConversationGroupService.class);
        JdbcConversationMemoryStore store = new JdbcConversationMemoryStore(
                conversationService,
                messageService,
                groupService
        );
        when(groupService.findLatestSummary("conv-1", "user-1")).thenReturn(null);
        when(groupService.listMessagesBetweenIds("conv-1", "user-1", null, null)).thenReturn(List.of(
                messageDO("1", "user", "第 1 轮问题"),
                messageDO("2", "assistant", "第 1 轮回答"),
                messageDO("3", "user", "第 2 轮问题"),
                messageDO("4", "assistant", "第 2 轮回答"),
                messageDO("5", "user", "第 3 轮问题"),
                messageDO("6", "assistant", "第 3 轮回答"),
                messageDO("7", "user", "第 4 轮问题"),
                messageDO("8", "assistant", "第 4 轮回答"),
                messageDO("9", "user", "第 5 轮问题"),
                messageDO("10", "assistant", "第 5 轮回答")
        ));

        List<ChatMessage> history = store.loadHistory("conv-1", "user-1");

        Assertions.assertEquals(10, history.size());
        Assertions.assertEquals("第 1 轮问题", history.get(0).getContent());
        Assertions.assertEquals("第 5 轮回答", history.get(9).getContent());
        verify(groupService).listMessagesBetweenIds("conv-1", "user-1", null, null);
        verifyNoInteractions(messageService);
    }

    private ConversationMessageDO messageDO(String id, String role, String content) {
        return ConversationMessageDO.builder()
                .id(id)
                .role(role)
                .content(content)
                .build();
    }

}
