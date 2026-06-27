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

import java.util.List;

/**
 * 对话记忆服务接口
 * 负责管理和存储用户对话历史记录，提供对话上下文的加载、追加等功能
 */
public interface ConversationMemoryService {

    /**
     * 加载对话历史记录
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 对话历史消息列表（包含摘要和历史记录）
     */
    default List<ChatMessage> load(String conversationId, String userId) {
        return loadContext(conversationId, userId).recentHistory();
    }

    /**
     * 加载对话记忆上下文，摘要和最近原文历史分开返回。
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @return 对话记忆上下文
     */
    ConversationMemoryContext loadContext(String conversationId, String userId);

    /**
     * 追加消息到对话历史
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param message        要追加的消息
     * @return 消息ID
     */
    String append(String conversationId, String userId, ChatMessage message);

    /**
     * 加载历史并追加新消息（便捷方法）
     * <p>
     * 适用于需要同时获取历史和追加消息的场景，避免重复调用 load() 和 append()
     * </p>
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param message        要追加的消息
     * @return 包含追加前的历史记录
     */
    default List<ChatMessage> loadAndAppend(String conversationId, String userId, ChatMessage message) {
        ConversationMemoryContext memoryContext = loadContextAndAppend(conversationId, userId, message);
        return memoryContext.recentHistory();
    }

    /**
     * 加载记忆上下文并追加新消息。
     *
     * @param conversationId 对话ID
     * @param userId         用户ID
     * @param message        要追加的消息
     * @return 追加前的对话记忆上下文
     */
    default ConversationMemoryContext loadContextAndAppend(String conversationId, String userId, ChatMessage message) {
        ConversationMemoryContext memoryContext = loadContext(conversationId, userId);
        append(conversationId, userId, message);
        return memoryContext;
    }
}
