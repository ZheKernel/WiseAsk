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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryContext;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.memory.LongTermMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StreamChatPipelineTest {

    @Test
    void shouldLoadSeparatedMemoryContextAndAppendCurrentUserQuestionBeforeRewrite() {
        SearchChannelProperties searchProperties = new SearchChannelProperties();
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
        IntentResolver intentResolver = mock(IntentResolver.class);
        IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
        RetrievalEngine retrievalEngine = mock(RetrievalEngine.class);
        LLMService llmService = mock(LLMService.class);
        RAGPromptService promptBuilder = mock(RAGPromptService.class);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        StreamTaskManager taskManager = mock(StreamTaskManager.class);
        StreamCallback callback = mock(StreamCallback.class);

        List<ChatMessage> recentHistory = List.of(
                ChatMessage.user("上一轮问题"),
                ChatMessage.assistant("上一轮回答")
        );
        when(memoryService.loadContextAndAppend(eq("conv-1"), eq("user-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ConversationMemoryContext("压缩摘要", recentHistory));
        when(queryRewriteService.rewriteWithSplit(eq("当前问题"), eq(recentHistory)))
                .thenReturn(new RewriteResult("当前问题", List.of("当前问题")));
        when(longTermMemoryService.recall("user-1", "当前问题"))
                .thenReturn("<long-term-memory>\n- [PREFERENCE] prefers Java\n</long-term-memory>");
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("当前问题", List.of()));
        when(intentResolver.resolve(new RewriteResult("当前问题", List.of("当前问题")))).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity("当前问题", subIntents)).thenReturn(GuidanceDecision.none());
        when(retrievalEngine.retrieve(eq(subIntents), anyInt())).thenReturn(RetrievalContext.builder()
                .intentChunks(Map.of())
                .build());

        StreamChatPipeline pipeline = new StreamChatPipeline(
                searchProperties,
                memoryService,
                longTermMemoryService,
                queryRewriteService,
                intentResolver,
                guidanceService,
                retrievalEngine,
                llmService,
                promptBuilder,
                promptTemplateLoader,
                taskManager
        );
        StreamChatContext ctx = StreamChatContext.builder()
                .conversationId("conv-1")
                .userId("user-1")
                .taskId("task-1")
                .question("当前问题")
                .callback(callback)
                .build();

        pipeline.execute(ctx);

        ArgumentCaptor<ChatMessage> appendedMessage = ArgumentCaptor.forClass(ChatMessage.class);
        verify(memoryService).loadContextAndAppend(eq("conv-1"), eq("user-1"), appendedMessage.capture());
        Assertions.assertEquals(ChatMessage.Role.USER, appendedMessage.getValue().getRole());
        Assertions.assertEquals("当前问题", appendedMessage.getValue().getContent());
        Assertions.assertEquals("压缩摘要", ctx.getConversationSummary());
        Assertions.assertEquals("<long-term-memory>\n- [PREFERENCE] prefers Java\n</long-term-memory>",
                ctx.getLongTermMemory());
        Assertions.assertEquals(recentHistory, ctx.getHistory());
        verify(queryRewriteService).rewriteWithSplit("当前问题", recentHistory);
        verify(longTermMemoryService).recall("user-1", "当前问题");
        verify(callback).onContent("未检索到与问题相关的文档内容。");
        verify(callback).onComplete();
        verifyNoInteractions(llmService, promptBuilder, promptTemplateLoader, taskManager);
    }
}
