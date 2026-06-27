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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentDirectedSearchChannelTest {

    private final RetrieverService retrieverService = mock(RetrieverService.class);

    @Test
    void shouldSkipUnauthorizedIntentCollections() {
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());
        IntentDirectedSearchChannel channel = new IntentDirectedSearchChannel(
                retrieverService,
                properties(),
                Runnable::run
        );
        SearchContext context = SearchContext.builder()
                .rewrittenQuestion("question")
                .topK(3)
                .authorizedCollections(Set.of("allowed_collection"))
                .intents(List.of(new SubQuestionIntent("question", List.of(
                        score(node("allowed_collection")),
                        score(node("denied_collection"))
                ))))
                .build();

        channel.search(context);

        verify(retrieverService).retrieve(argThat(req -> "allowed_collection".equals(req.getCollectionName())));
        verify(retrieverService, never()).retrieve(argThat(req -> "denied_collection".equals(req.getCollectionName())));
    }

    private SearchChannelProperties properties() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getIntentDirected().setEnabled(true);
        properties.getChannels().getIntentDirected().setTopKMultiplier(1);
        return properties;
    }

    private NodeScore score(IntentNode node) {
        return NodeScore.builder().node(node).score(0.9).build();
    }

    private IntentNode node(String collectionName) {
        return IntentNode.builder()
                .id(collectionName)
                .name(collectionName)
                .collectionName(collectionName)
                .build();
    }
}
