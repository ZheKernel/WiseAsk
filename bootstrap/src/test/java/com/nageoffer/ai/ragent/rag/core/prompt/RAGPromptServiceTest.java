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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.RAG_ENTERPRISE_PROMPT_PATH;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RAGPromptServiceTest {

    @Test
    void shouldEmitConversationSummaryAsSemiStableContextBeforeHistory() {
        PromptTemplateLoader templateLoader = mock(PromptTemplateLoader.class);
        when(templateLoader.load(RAG_ENTERPRISE_PROMPT_PATH)).thenReturn("system base prompt");
        when(templateLoader.renderSection(eq(CONTEXT_FORMAT_PATH), anyString(), anyMap()))
                .thenAnswer(invocation -> renderSection(
                        invocation.getArgument(1),
                        invocation.getArgument(2)
                ));

        RAGPromptService promptService = new RAGPromptService(templateLoader);
        PromptContext context = PromptContext.builder()
                .conversationSummary("  用户偏好 Java，之前问过 OA 权限。  ")
                .kbContext("知识库片段")
                .build();
        List<ChatMessage> history = List.of(
                ChatMessage.user("上一轮问题"),
                ChatMessage.assistant("上一轮回答")
        );

        List<ChatMessage> messages = promptService.buildStructuredMessages(
                context,
                history,
                "本轮问题是什么？",
                List.of()
        );

        Assertions.assertEquals(5, messages.size());
        Assertions.assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        Assertions.assertEquals("system base prompt", messages.get(0).getContent());
        Assertions.assertEquals(ChatMessage.Role.USER, messages.get(1).getRole());
        Assertions.assertTrue(messages.get(1).getContent().contains("<conversation-summary>"));
        Assertions.assertTrue(messages.get(1).getContent().contains("用户偏好 Java，之前问过 OA 权限。"));
        Assertions.assertEquals(ChatMessage.Role.USER, messages.get(2).getRole());
        Assertions.assertEquals("上一轮问题", messages.get(2).getContent());
        Assertions.assertEquals(ChatMessage.Role.ASSISTANT, messages.get(3).getRole());
        Assertions.assertEquals("上一轮回答", messages.get(3).getContent());
        Assertions.assertEquals(ChatMessage.Role.USER, messages.get(4).getRole());
        Assertions.assertFalse(messages.get(0).getContent().contains("用户偏好 Java"));
        Assertions.assertFalse(messages.get(2).getContent().contains("用户偏好 Java"));
        Assertions.assertFalse(messages.get(3).getContent().contains("用户偏好 Java"));

        String finalUserContent = messages.get(4).getContent();
        Assertions.assertFalse(finalUserContent.contains("<conversation-summary>"));
        Assertions.assertTrue(finalUserContent.contains("<kb-evidence>"));
        Assertions.assertTrue(finalUserContent.contains("知识库片段"));
        Assertions.assertTrue(finalUserContent.contains("<single-question>"));
        Assertions.assertTrue(finalUserContent.contains("本轮问题是什么？"));
    }

    @Test
    void shouldSkipSemiStableContextWhenSummaryIsBlank() {
        PromptTemplateLoader templateLoader = mock(PromptTemplateLoader.class);
        when(templateLoader.load(RAG_ENTERPRISE_PROMPT_PATH)).thenReturn("system base prompt");
        when(templateLoader.renderSection(eq(CONTEXT_FORMAT_PATH), anyString(), anyMap()))
                .thenAnswer(invocation -> renderSection(
                        invocation.getArgument(1),
                        invocation.getArgument(2)
                ));

        RAGPromptService promptService = new RAGPromptService(templateLoader);
        PromptContext context = PromptContext.builder()
                .conversationSummary("   ")
                .kbContext("知识库片段")
                .build();

        List<ChatMessage> messages = promptService.buildStructuredMessages(
                context,
                List.of(),
                "本轮问题是什么？",
                List.of()
        );

        Assertions.assertEquals(2, messages.size());
        Assertions.assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        Assertions.assertEquals("system base prompt", messages.get(0).getContent());
        Assertions.assertEquals(ChatMessage.Role.USER, messages.get(1).getRole());
        Assertions.assertFalse(messages.get(1).getContent().contains("<conversation-summary>"));
        Assertions.assertTrue(messages.get(1).getContent().contains("<kb-evidence>"));
        Assertions.assertTrue(messages.get(1).getContent().contains("知识库片段"));
        Assertions.assertTrue(messages.get(1).getContent().contains("<single-question>"));
        Assertions.assertTrue(messages.get(1).getContent().contains("本轮问题是什么？"));
    }

    @Test
    void shouldPlaceLongTermMemoryBeforeConversationSummary() {
        PromptTemplateLoader templateLoader = mock(PromptTemplateLoader.class);
        when(templateLoader.load(RAG_ENTERPRISE_PROMPT_PATH)).thenReturn("system base prompt");
        when(templateLoader.renderSection(eq(CONTEXT_FORMAT_PATH), anyString(), anyMap()))
                .thenAnswer(invocation -> renderSection(
                        invocation.getArgument(1),
                        invocation.getArgument(2)
                ));

        RAGPromptService promptService = new RAGPromptService(templateLoader);
        PromptContext context = PromptContext.builder()
                .longTermMemory("""
                        <long-term-memory>
                        - [PREFERENCE] User prefers Java/Spring style implementations.
                        </long-term-memory>
                        """.trim())
                .conversationSummary("用户之前讨论了 RAG 架构优化。")
                .kbContext("知识库片段")
                .build();
        List<ChatMessage> history = List.of(
                ChatMessage.user("上一轮问题"),
                ChatMessage.assistant("上一轮回答")
        );

        List<ChatMessage> messages = promptService.buildStructuredMessages(
                context,
                history,
                "本轮问题是什么？",
                List.of()
        );

        Assertions.assertEquals(6, messages.size());
        Assertions.assertEquals(ChatMessage.Role.SYSTEM, messages.get(0).getRole());
        Assertions.assertEquals(ChatMessage.Role.USER, messages.get(1).getRole());
        Assertions.assertTrue(messages.get(1).getContent().contains("<long-term-memory>"));
        Assertions.assertTrue(messages.get(1).getContent().contains("User prefers Java/Spring style implementations."));
        Assertions.assertEquals(ChatMessage.Role.USER, messages.get(2).getRole());
        Assertions.assertTrue(messages.get(2).getContent().contains("<conversation-summary>"));
        Assertions.assertEquals("上一轮问题", messages.get(3).getContent());
        Assertions.assertEquals("上一轮回答", messages.get(4).getContent());
        Assertions.assertFalse(messages.get(5).getContent().contains("<long-term-memory>"));
        Assertions.assertFalse(messages.get(5).getContent().contains("<conversation-summary>"));
        Assertions.assertTrue(messages.get(5).getContent().contains("<kb-evidence>"));
        Assertions.assertTrue(messages.get(5).getContent().contains("<single-question>"));
    }

    private String renderSection(String section, Map<String, String> slots) {
        return switch (section) {
            case "summary-wrapper" -> "<conversation-summary>\n" + slots.get("content")
                    + "\n</conversation-summary>";
            case "kb-evidence" -> "<kb-evidence>\n" + slots.get("body") + "\n</kb-evidence>";
            case "single-question" -> "<single-question>\n" + slots.get("question") + "\n</single-question>";
            default -> throw new IllegalArgumentException("Unexpected section: " + section);
        };
    }
}
