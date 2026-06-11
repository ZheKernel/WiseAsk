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
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONVERSATION_SUMMARY_PROMPT_PATH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdbcConversationMemorySummaryServiceTest {

    @Test
    void shouldNotSummarizeBeforeEightUserTurns() {
        Fixture fixture = new Fixture();
        when(fixture.groupService.countUserMessages("conv-1", "user-1")).thenReturn(7L);

        fixture.service.compressIfNeeded("conv-1", "user-1", ChatMessage.assistant("answer"));

        verify(fixture.groupService).countUserMessages("conv-1", "user-1");
        verify(fixture.groupService, never()).findLatestSummary(anyString(), anyString());
        verifyNoInteractions(fixture.messageService, fixture.llmService);
    }

    @Test
    void shouldCreateFirstSummaryAtEightTurnsAndLeaveLatestFourTurnsAsRawHistory() {
        Fixture fixture = new Fixture();
        when(fixture.groupService.countUserMessages("conv-1", "user-1")).thenReturn(8L);
        when(fixture.groupService.findLatestSummary("conv-1", "user-1")).thenReturn(null);
        when(fixture.groupService.listLatestUserOnlyMessages("conv-1", "user-1", 4)).thenReturn(List.of(
                message("15", "user", "u8"),
                message("13", "user", "u7"),
                message("11", "user", "u6"),
                message("9", "user", "u5")
        ));
        when(fixture.groupService.listMessagesBetweenIds("conv-1", "user-1", null, "9"))
                .thenReturn(turns(1, 4));
        when(fixture.llmService.chat(any(ChatRequest.class))).thenReturn("summary-1");

        fixture.service.compressIfNeeded("conv-1", "user-1", ChatMessage.assistant("answer"));

        verify(fixture.groupService).listMessagesBetweenIds("conv-1", "user-1", null, "9");
        ArgumentCaptor<ConversationSummaryBO> summary = ArgumentCaptor.forClass(ConversationSummaryBO.class);
        verify(fixture.messageService).addMessageSummary(summary.capture());
        Assertions.assertEquals("summary-1", summary.getValue().getContent());
        Assertions.assertEquals("8", summary.getValue().getLastMessageId());
    }

    @Test
    void shouldSkipExistingSummaryWhenNewTurnsAreLessThanSix() {
        Fixture fixture = new Fixture();
        ConversationSummaryDO latestSummary = ConversationSummaryDO.builder()
                .content("old summary")
                .lastMessageId("8")
                .build();
        when(fixture.groupService.countUserMessages("conv-1", "user-1")).thenReturn(13L);
        when(fixture.groupService.findLatestSummary("conv-1", "user-1")).thenReturn(latestSummary);
        when(fixture.groupService.listLatestUserOnlyMessages("conv-1", "user-1", 4)).thenReturn(List.of(
                message("25", "user", "u13"),
                message("23", "user", "u12"),
                message("21", "user", "u11"),
                message("19", "user", "u10")
        ));
        when(fixture.groupService.listMessagesBetweenIds("conv-1", "user-1", "8", "19"))
                .thenReturn(turns(5, 9));

        fixture.service.compressIfNeeded("conv-1", "user-1", ChatMessage.assistant("answer"));

        verify(fixture.groupService).listMessagesBetweenIds("conv-1", "user-1", "8", "19");
        verify(fixture.llmService, never()).chat(any(ChatRequest.class));
        verify(fixture.messageService, never()).addMessageSummary(any());
    }

    @Test
    void shouldUpdateExistingSummaryEverySixTurnsFromLastSummaryMessageId() {
        Fixture fixture = new Fixture();
        ConversationSummaryDO latestSummary = ConversationSummaryDO.builder()
                .content("old summary")
                .lastMessageId("8")
                .build();
        when(fixture.groupService.countUserMessages("conv-1", "user-1")).thenReturn(14L);
        when(fixture.groupService.findLatestSummary("conv-1", "user-1")).thenReturn(latestSummary);
        when(fixture.groupService.listLatestUserOnlyMessages("conv-1", "user-1", 4)).thenReturn(List.of(
                message("27", "user", "u14"),
                message("25", "user", "u13"),
                message("23", "user", "u12"),
                message("21", "user", "u11")
        ));
        when(fixture.groupService.listMessagesBetweenIds("conv-1", "user-1", "8", "21"))
                .thenReturn(turns(5, 10));
        when(fixture.llmService.chat(any(ChatRequest.class))).thenReturn("summary-2");

        fixture.service.compressIfNeeded("conv-1", "user-1", ChatMessage.assistant("answer"));

        verify(fixture.groupService).listMessagesBetweenIds("conv-1", "user-1", "8", "21");
        ArgumentCaptor<ConversationSummaryBO> summary = ArgumentCaptor.forClass(ConversationSummaryBO.class);
        verify(fixture.messageService).addMessageSummary(summary.capture());
        Assertions.assertEquals("summary-2", summary.getValue().getContent());
        Assertions.assertEquals("20", summary.getValue().getLastMessageId());
    }

    private static ConversationMessageDO message(String id, String role, String content) {
        return ConversationMessageDO.builder()
                .id(id)
                .role(role)
                .content(content)
                .build();
    }

    private static List<ConversationMessageDO> turns(int startTurn, int endTurn) {
        List<ConversationMessageDO> messages = new ArrayList<>();
        for (int turn = startTurn; turn <= endTurn; turn++) {
            messages.add(message(String.valueOf(turn * 2 - 1), "user", "u" + turn));
            messages.add(message(String.valueOf(turn * 2), "assistant", "a" + turn));
        }
        return messages;
    }

    private static class Fixture {

        private final ConversationGroupService groupService = mock(ConversationGroupService.class);
        private final ConversationMessageService messageService = mock(ConversationMessageService.class);
        private final LLMService llmService = mock(LLMService.class);
        private final TokenCounterService tokenCounterService = mock(TokenCounterService.class);
        private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        private final RedissonClient redissonClient = mock(RedissonClient.class);
        private final RLock lock = mock(RLock.class);
        private final MemoryProperties memoryProperties = new MemoryProperties();
        private final Executor directExecutor = Runnable::run;
        private final JdbcConversationMemorySummaryService service;

        private Fixture() {
            memoryProperties.setSummaryEnabled(true);
            memoryProperties.setHistoryKeepTurns(4);
            memoryProperties.setSummaryStartTurns(8);
            memoryProperties.setSummaryUpdateMinTurns(6);
            memoryProperties.setSummaryTriggerInputTokens(12000);
            memoryProperties.setSummaryMaxChars(200);
            when(redissonClient.getLock(anyString())).thenReturn(lock);
            when(lock.tryLock()).thenReturn(true);
            when(lock.isHeldByCurrentThread()).thenReturn(true);
            when(tokenCounterService.countTokens(anyString())).thenReturn(1);
            when(promptTemplateLoader.render(eq(CONVERSATION_SUMMARY_PROMPT_PATH), any()))
                    .thenReturn("summary prompt");
            service = new JdbcConversationMemorySummaryService(
                    groupService,
                    messageService,
                    memoryProperties,
                    llmService,
                    tokenCounterService,
                    promptTemplateLoader,
                    redissonClient,
                    directExecutor
            );
        }
    }
}
