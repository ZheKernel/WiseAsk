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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.UserLongTermMemoryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.UserLongTermMemoryMapper;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.LONG_TERM_MEMORY_EXTRACT_PROMPT_PATH;

@Slf4j
@Service
@RequiredArgsConstructor
public class JdbcLongTermMemoryService implements LongTermMemoryService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final Set<String> SUPPORTED_TYPES = Set.of("PREFERENCE", "PROJECT", "CONSTRAINT", "FACT");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}_]+");
    private static final Pattern MEMORY_KEY_CLEANUP = Pattern.compile("[^a-z0-9_]+");

    private final UserLongTermMemoryMapper memoryMapper;
    private final ConversationMessageMapper messageMapper;
    private final MemoryProperties memoryProperties;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    @Qualifier("longTermMemoryExecutor")
    private final Executor longTermMemoryExecutor;

    @Override
    public String recall(String userId, String query) {
        if (!Boolean.TRUE.equals(memoryProperties.getLongTermEnabled()) || StrUtil.isBlank(userId)) {
            return null;
        }
        List<UserLongTermMemoryDO> records = memoryMapper.selectList(
                Wrappers.lambdaQuery(UserLongTermMemoryDO.class)
                        .eq(UserLongTermMemoryDO::getUserId, userId)
                        .eq(UserLongTermMemoryDO::getStatus, STATUS_ACTIVE)
                        .eq(UserLongTermMemoryDO::getDeleted, 0)
        );
        if (CollUtil.isEmpty(records)) {
            return null;
        }

        int limit = Math.max(1, memoryProperties.getLongTermRecallLimit());
        List<UserLongTermMemoryDO> selected = records.stream()
                .filter(this::isUsableMemory)
                .map(record -> new ScoredMemory(record, score(record, query)))
                .sorted(Comparator.comparingInt(ScoredMemory::score).reversed())
                .limit(limit)
                .map(ScoredMemory::memory)
                .toList();
        if (CollUtil.isEmpty(selected)) {
            return null;
        }

        refreshAccessMetadata(selected);
        return formatMemories(selected);
    }

    @Override
    public void extractAsync(String conversationId, String userId, String sourceMessageId) {
        if (!Boolean.TRUE.equals(memoryProperties.getLongTermEnabled())
                || !Boolean.TRUE.equals(memoryProperties.getLongTermExtractionEnabled())
                || StrUtil.isBlank(conversationId)
                || StrUtil.isBlank(userId)
                || StrUtil.isBlank(sourceMessageId)) {
            return;
        }
        CompletableFuture.runAsync(
                () -> doExtract(conversationId, userId, sourceMessageId),
                longTermMemoryExecutor
        ).exceptionally(ex -> {
            log.warn("长期记忆异步抽取失败 - conversationId: {}, userId: {}", conversationId, userId, ex);
            return null;
        });
    }

    private void doExtract(String conversationId, String userId, String sourceMessageId) {
        List<ConversationMessageDO> messages = loadRecentMessages(conversationId, userId, sourceMessageId);
        if (CollUtil.isEmpty(messages)) {
            return;
        }
        ChatRequest request = buildExtractionRequest(messages);
        String raw = llmService.chat(request);
        List<LongTermMemoryCandidate> candidates = parseCandidates(raw);
        if (CollUtil.isEmpty(candidates)) {
            return;
        }
        for (LongTermMemoryCandidate candidate : candidates) {
            upsertCandidate(userId, conversationId, sourceMessageId, candidate);
        }
    }

    private List<ConversationMessageDO> loadRecentMessages(String conversationId, String userId, String sourceMessageId) {
        int limit = Math.max(2, memoryProperties.getLongTermExtractionRecentMessages());
        List<ConversationMessageDO> records = messageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .in(ConversationMessageDO::getRole, "user", "assistant")
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .le(ConversationMessageDO::getId, sourceMessageId)
                        .orderByDesc(ConversationMessageDO::getId)
                        .last("limit " + limit)
        );
        if (CollUtil.isEmpty(records)) {
            return List.of();
        }
        List<ConversationMessageDO> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparing(ConversationMessageDO::getId));
        return ordered;
    }

    private ChatRequest buildExtractionRequest(List<ConversationMessageDO> messages) {
        String systemPrompt = promptTemplateLoader.render(
                LONG_TERM_MEMORY_EXTRACT_PROMPT_PATH,
                Map.of("max_content_chars", String.valueOf(memoryProperties.getLongTermMaxContentLength()))
        );
        String transcript = messages.stream()
                .map(this::formatTranscriptLine)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n"));
        return ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user("<conversation>\n" + transcript + "\n</conversation>")
                ))
                .temperature(0D)
                .topP(1D)
                .thinking(false)
                .build();
    }

    private String formatTranscriptLine(ConversationMessageDO message) {
        if (message == null || StrUtil.isBlank(message.getRole()) || StrUtil.isBlank(message.getContent())) {
            return "";
        }
        return message.getRole().toLowerCase(Locale.ROOT) + ": " + message.getContent().trim();
    }

    private List<LongTermMemoryCandidate> parseCandidates(String raw) {
        try {
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return List.of();
            }
            JsonElement memories = root.getAsJsonObject().get("memories");
            if (memories == null || !memories.isJsonArray()) {
                return List.of();
            }
            List<LongTermMemoryCandidate> result = new ArrayList<>();
            JsonArray array = memories.getAsJsonArray();
            for (JsonElement element : array) {
                LongTermMemoryCandidate candidate = parseCandidate(element);
                if (candidate != null) {
                    result.add(candidate);
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("解析长期记忆抽取结果失败，raw={}", raw, e);
            return List.of();
        }
    }

    private LongTermMemoryCandidate parseCandidate(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String type = readString(obj, "type").toUpperCase(Locale.ROOT);
        String key = normalizeKey(readString(obj, "key"));
        String content = truncate(readString(obj, "content"));
        if (!SUPPORTED_TYPES.contains(type) || StrUtil.isBlank(key) || StrUtil.isBlank(content)) {
            return null;
        }
        return new LongTermMemoryCandidate(
                type,
                key,
                content,
                clamp(readInt(obj, "confidence", 3)),
                clamp(readInt(obj, "importance", 3))
        );
    }

    private void upsertCandidate(String userId,
                                 String conversationId,
                                 String sourceMessageId,
                                 LongTermMemoryCandidate candidate) {
        UserLongTermMemoryDO existing = memoryMapper.selectOne(
                Wrappers.lambdaQuery(UserLongTermMemoryDO.class)
                        .eq(UserLongTermMemoryDO::getUserId, userId)
                        .eq(UserLongTermMemoryDO::getMemoryType, candidate.type())
                        .eq(UserLongTermMemoryDO::getMemoryKey, candidate.key())
                        .eq(UserLongTermMemoryDO::getDeleted, 0)
                        .last("limit 1")
        );
        if (existing == null) {
            memoryMapper.insert(UserLongTermMemoryDO.builder()
                    .userId(userId)
                    .memoryType(candidate.type())
                    .memoryKey(candidate.key())
                    .content(candidate.content())
                    .confidence(candidate.confidence())
                    .importance(candidate.importance())
                    .sourceConversationId(conversationId)
                    .sourceMessageId(sourceMessageId)
                    .accessCount(0)
                    .status(STATUS_ACTIVE)
                    .build());
            return;
        }

        UserLongTermMemoryDO update = UserLongTermMemoryDO.builder()
                .id(existing.getId())
                .content(selectContent(existing.getContent(), candidate.content()))
                .confidence(Math.max(valueOrDefault(existing.getConfidence(), 0), candidate.confidence()))
                .importance(Math.max(valueOrDefault(existing.getImportance(), 0), candidate.importance()))
                .sourceConversationId(conversationId)
                .sourceMessageId(sourceMessageId)
                .status(STATUS_ACTIVE)
                .build();
        memoryMapper.updateById(update);
    }

    private boolean isUsableMemory(UserLongTermMemoryDO record) {
        return record != null
                && StrUtil.isNotBlank(record.getContent())
                && StrUtil.isNotBlank(record.getMemoryType());
    }

    private int score(UserLongTermMemoryDO record, String query) {
        int relevance = relevanceScore(record, query);
        return relevance * 100
                + valueOrDefault(record.getImportance(), 0) * 10
                + valueOrDefault(record.getConfidence(), 0) * 5
                + valueOrDefault(record.getAccessCount(), 0);
    }

    private int relevanceScore(UserLongTermMemoryDO record, String query) {
        if (StrUtil.isBlank(query)) {
            return 0;
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String target = (StrUtil.emptyIfNull(record.getMemoryKey()) + " "
                + StrUtil.emptyIfNull(record.getContent())).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokenize(normalizedQuery)) {
            if (target.contains(token)) {
                score++;
            }
        }
        for (String token : tokenize(target)) {
            if (token.length() > 2 && normalizedQuery.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private List<String> tokenize(String text) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        return TOKEN_SPLIT.splitAsStream(text.toLowerCase(Locale.ROOT))
                .map(String::trim)
                .filter(token -> token.length() > 1)
                .distinct()
                .toList();
    }

    private void refreshAccessMetadata(List<UserLongTermMemoryDO> selected) {
        Date now = new Date();
        for (UserLongTermMemoryDO record : selected) {
            UserLongTermMemoryDO update = UserLongTermMemoryDO.builder()
                    .id(record.getId())
                    .accessCount(valueOrDefault(record.getAccessCount(), 0) + 1)
                    .lastAccessTime(now)
                    .build();
            memoryMapper.updateById(update);
        }
    }

    private String formatMemories(List<UserLongTermMemoryDO> selected) {
        String body = selected.stream()
                .map(item -> "- [" + item.getMemoryType() + "] " + item.getContent().trim())
                .collect(Collectors.joining("\n"));
        return "<long-term-memory>\n" + body + "\n</long-term-memory>";
    }

    private String selectContent(String oldContent, String newContent) {
        if (StrUtil.isBlank(oldContent)) {
            return newContent;
        }
        return newContent.length() >= oldContent.length() ? newContent : oldContent;
    }

    private String readString(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private int readInt(JsonObject obj, String key, int fallback) {
        try {
            JsonElement value = obj.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }

    private String normalizeKey(String key) {
        if (StrUtil.isBlank(key)) {
            return "";
        }
        String cleaned = MEMORY_KEY_CLEANUP.matcher(key.toLowerCase(Locale.ROOT).trim()).replaceAll("_");
        cleaned = cleaned.replaceAll("_+", "_");
        cleaned = cleaned.replaceAll("^_+|_+$", "");
        return cleaned;
    }

    private String truncate(String content) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String trimmed = content.trim();
        int maxLength = Math.max(50, memoryProperties.getLongTermMaxContentLength());
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private record ScoredMemory(UserLongTermMemoryDO memory, int score) {
    }
}
