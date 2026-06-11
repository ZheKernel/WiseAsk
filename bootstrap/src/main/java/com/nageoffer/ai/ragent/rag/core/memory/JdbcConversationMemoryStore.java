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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationCreateBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class JdbcConversationMemoryStore implements ConversationMemoryStore {

    private final ConversationService conversationService;
    private final ConversationMessageService conversationMessageService;
    private final ConversationGroupService conversationGroupService;

    public JdbcConversationMemoryStore(ConversationService conversationService,
                                       ConversationMessageService conversationMessageService,
                                       ConversationGroupService conversationGroupService) {
        this.conversationService = conversationService;
        this.conversationMessageService = conversationMessageService;
        this.conversationGroupService = conversationGroupService;
    }

    @Override
    public List<ChatMessage> loadHistory(String conversationId, String userId) {
        String summaryLastMessageId = resolveSummaryLastMessageId(conversationId, userId);
        if (StrUtil.isNotBlank(summaryLastMessageId)) {
            return loadHistoryFromCheckpoint(conversationId, userId, summaryLastMessageId);
        }

        return loadHistoryFromCheckpoint(conversationId, userId, null);
    }

    @Override
    public String append(String conversationId, String userId, ChatMessage message) {
        ConversationMessageBO conversationMessage = ConversationMessageBO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .role(message.getRole().name().toLowerCase())
                .content(message.getContent())
                .thinkingContent(message.getThinkingContent())
                .thinkingDuration(message.getThinkingDuration())
                .build();
        String messageId = conversationMessageService.addMessage(conversationMessage);

        if (message.getRole() == ChatMessage.Role.USER) {
            ConversationCreateBO conversation = ConversationCreateBO.builder()
                    .conversationId(conversationId)
                    .userId(userId)
                    .question(message.getContent())
                    .lastTime(new Date())
                    .build();
            conversationService.createOrUpdate(conversation);
        }
        return messageId;
    }

    @Override
    public void refreshCache(String conversationId, String userId) {
        // JDBC 直读模式，无需刷新缓存
    }

    private ChatMessage toChatMessage(ConversationMessageDO record) {
        if (record == null || StrUtil.isBlank(record.getContent())) {
            return null;
        }
        return new ChatMessage(
                ChatMessage.Role.fromString(record.getRole()),
                record.getContent()
        );
    }

    private List<ChatMessage> loadHistoryFromCheckpoint(String conversationId, String userId, String afterId) {
        List<ConversationMessageDO> dbMessages = conversationGroupService.listMessagesBetweenIds(
                conversationId,
                userId,
                afterId,
                null
        );
        if (CollUtil.isEmpty(dbMessages)) {
            return List.of();
        }
        List<ChatMessage> result = dbMessages.stream()
                .map(this::toChatMessage)
                .filter(Objects::nonNull)
                .filter(this::isHistoryMessage)
                .collect(Collectors.toList());
        return normalizeHistory(result);
    }

    private String resolveSummaryLastMessageId(String conversationId, String userId) {
        ConversationSummaryDO summary = conversationGroupService.findLatestSummary(conversationId, userId);
        if (summary == null) {
            return null;
        }
        if (StrUtil.isNotBlank(summary.getLastMessageId())) {
            return summary.getLastMessageId();
        }
        Date summaryTime = summary.getUpdateTime() == null ? summary.getCreateTime() : summary.getUpdateTime();
        return conversationGroupService.findMaxMessageIdAtOrBefore(conversationId, userId, summaryTime);
    }

    private List<ChatMessage> normalizeHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = 0;
        while (start < messages.size() && messages.get(start).getRole() == ChatMessage.Role.ASSISTANT) {
            start++;
        }
        if (start >= messages.size()) {
            return List.of();
        }
        return messages.subList(start, messages.size());
    }

    private boolean isHistoryMessage(ChatMessage message) {
        return message != null
                && (message.getRole() == ChatMessage.Role.USER || message.getRole() == ChatMessage.Role.ASSISTANT)
                && StrUtil.isNotBlank(message.getContent());
    }

}
