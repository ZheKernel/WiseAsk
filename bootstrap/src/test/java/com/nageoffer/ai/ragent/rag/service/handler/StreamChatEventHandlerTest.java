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

package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.memory.LongTermMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamChatEventHandlerTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldTriggerLongTermMemoryExtractionAfterAssistantMessagePersisted() {
        UserContext.set(LoginUser.builder()
                .userId("user-1")
                .username("alice")
                .role("user")
                .build());
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        ConversationGroupService conversationGroupService = mock(ConversationGroupService.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        AIModelProperties modelProperties = new AIModelProperties();
        modelProperties.getStream().setMessageChunkSize(100);
        when(taskManager.isCancelled("task-1")).thenReturn(false);
        when(conversationGroupService.findConversation("conv-1", "user-1")).thenReturn(null);
        when(memoryService.append(eq("conv-1"), eq("user-1"), any(ChatMessage.class)))
                .thenReturn("assistant-msg-1");

        StreamChatEventHandler handler = new StreamChatEventHandler(StreamChatHandlerParams.builder()
                .emitter(new SseEmitter())
                .conversationId("conv-1")
                .taskId("task-1")
                .modelProperties(modelProperties)
                .memoryService(memoryService)
                .longTermMemoryService(longTermMemoryService)
                .conversationGroupService(conversationGroupService)
                .taskManager(taskManager)
                .build());

        handler.onContent("final answer");
        handler.onComplete();

        ArgumentCaptor<ChatMessage> persistedMessage = ArgumentCaptor.forClass(ChatMessage.class);
        InOrder order = inOrder(memoryService, longTermMemoryService, taskManager);
        order.verify(memoryService).append(eq("conv-1"), eq("user-1"), persistedMessage.capture());
        order.verify(longTermMemoryService).extractAsync("conv-1", "user-1", "assistant-msg-1");
        order.verify(taskManager).unregister("task-1");
        Assertions.assertEquals(ChatMessage.Role.ASSISTANT, persistedMessage.getValue().getRole());
        Assertions.assertEquals("final answer", persistedMessage.getValue().getContent());
    }
}
