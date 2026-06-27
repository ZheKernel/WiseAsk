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
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class VectorGlobalSearchChannelTest {

    @Test
    void shouldSearchOnlyAuthorizedCollections() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());
        VectorGlobalSearchChannel channel = new VectorGlobalSearchChannel(
                retrieverService,
                properties(),
                Runnable::run
        );
        SearchContext context = SearchContext.builder()
                .rewrittenQuestion("question")
                .topK(3)
                .authorizedCollections(Set.of("global_collection", "own_collection"))
                .build();

        channel.search(context);

        verify(retrieverService).retrieve(argThat(req -> "global_collection".equals(req.getCollectionName())));
        verify(retrieverService).retrieve(argThat(req -> "own_collection".equals(req.getCollectionName())));
        verifyNoMoreInteractions(retrieverService);
    }

    private SearchChannelProperties properties() {
        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().getVectorGlobal().setEnabled(true);
        properties.getChannels().getVectorGlobal().setTopKMultiplier(1);
        properties.getChannels().getIntentDirected().setEnabled(false);
        return properties;
    }
}
