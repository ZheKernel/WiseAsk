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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_CLASSIFIER_PROMPT_PATH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultIntentClassifierTest {

    @Test
    void shouldRenderCachedIntentLeavesInStablePreorder() {
        LLMService llmService = mock(LLMService.class);
        IntentNodeMapper intentNodeMapper = mock(IntentNodeMapper.class);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        IntentTreeCacheManager cacheManager = mock(IntentTreeCacheManager.class);

        IntentNode leafA2 = leaf("a-2", "A second", 20);
        IntentNode leafA1 = leaf("a-1", "A first", 10);
        IntentNode rootA = node("root-a", "Root A", 10, leafA2, leafA1);
        IntentNode rootB = node("root-b", "Root B", 20, leaf("b-1", "B first", 10));
        when(cacheManager.getIntentTreeFromCache()).thenReturn(new ArrayList<>(List.of(rootB, rootA)));
        when(promptTemplateLoader.render(eq(INTENT_CLASSIFIER_PROMPT_PATH), anyMap()))
                .thenAnswer(invocation -> {
                    Map<String, String> slots = invocation.getArgument(1);
                    return slots.get("intent_list");
                });
        when(llmService.chat(any(ChatRequest.class))).thenReturn("""
                [
                  {"id": "a-1", "score": 0.9},
                  {"id": "a-2", "score": 0.8},
                  {"id": "b-1", "score": 0.7}
                ]
                """);

        DefaultIntentClassifier classifier = new DefaultIntentClassifier(
                llmService,
                intentNodeMapper,
                promptTemplateLoader,
                cacheManager
        );

        List<NodeScore> scores = classifier.classifyTargets("怎么处理 OA 权限？");

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(llmService).chat(requestCaptor.capture());
        ChatMessage systemMessage = requestCaptor.getValue().getMessages().get(0);
        String prompt = systemMessage.getContent();
        Assertions.assertEquals(ChatMessage.Role.SYSTEM, systemMessage.getRole());
        Assertions.assertTrue(prompt.indexOf("id=a-1") < prompt.indexOf("id=a-2"));
        Assertions.assertTrue(prompt.indexOf("id=a-2") < prompt.indexOf("id=b-1"));
        Assertions.assertEquals(List.of("a-1", "a-2", "b-1"),
                scores.stream().map(score -> score.getNode().getId()).toList());
    }

    private IntentNode node(String id, String name, Integer sortOrder, IntentNode... children) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .sortOrder(sortOrder)
                .description("description " + id)
                .fullPath(name)
                .children(new ArrayList<>(List.of(children)))
                .build();
    }

    private IntentNode leaf(String id, String name, Integer sortOrder) {
        return node(id, name, sortOrder);
    }
}
