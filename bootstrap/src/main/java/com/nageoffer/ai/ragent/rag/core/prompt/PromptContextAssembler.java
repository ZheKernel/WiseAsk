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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;

import java.util.ArrayList;
import java.util.List;

final class PromptContextAssembler {

    List<ChatMessage> assemble(List<PromptContextLayer> leadingLayers,
                               List<ChatMessage> history,
                               PromptContextLayer finalLayer) {
        List<ChatMessage> messages = new ArrayList<>();
        appendLayers(messages, leadingLayers);
        appendHistory(messages, history);
        appendLayer(messages, finalLayer);
        return messages;
    }

    private void appendLayers(List<ChatMessage> messages, List<PromptContextLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            return;
        }
        layers.forEach(layer -> appendLayer(messages, layer));
    }

    private void appendHistory(List<ChatMessage> messages, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        history.stream()
                .filter(message -> message != null && StrUtil.isNotBlank(message.getContent()))
                .forEach(messages::add);
    }

    private void appendLayer(List<ChatMessage> messages, PromptContextLayer layer) {
        if (layer == null || StrUtil.isBlank(layer.content())) {
            return;
        }
        messages.add(new ChatMessage(layer.role(), layer.content()));
    }
}
