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
 * 对话记忆上下文：把压缩摘要和最近原文历史分开承载。
 */
public record ConversationMemoryContext(String summary, List<ChatMessage> recentHistory) {

    public ConversationMemoryContext {
        recentHistory = recentHistory == null ? List.of() : List.copyOf(recentHistory);
    }

    public static ConversationMemoryContext empty() {
        return new ConversationMemoryContext(null, List.of());
    }
}
