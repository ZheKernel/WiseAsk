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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.RagCapabilitiesVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.util.List;

class RAGSettingsControllerTest {

    @Test
    void shouldExposeOnlyEnabledEmbeddingModelsInCapabilities() {
        AIModelProperties aiModelProperties = new AIModelProperties();
        aiModelProperties.getEmbedding().setDefaultModel("embed-enabled");
        aiModelProperties.getEmbedding().setCandidates(List.of(
                model("embed-enabled", "provider-a", "model-a", true),
                model("embed-disabled", "provider-b", "model-b", false)
        ));
        RAGSettingsController controller = new RAGSettingsController(
                new RAGDefaultProperties(),
                new RAGConfigProperties(),
                new RAGRateLimitProperties(),
                new MemoryProperties(),
                aiModelProperties
        );
        ReflectionTestUtils.setField(controller, "maxFileSize", DataSize.ofMegabytes(50));
        ReflectionTestUtils.setField(controller, "maxRequestSize", DataSize.ofMegabytes(100));

        Result<RagCapabilitiesVO> result = controller.capabilities();

        Assertions.assertTrue(result.isSuccess());
        Assertions.assertEquals("embed-enabled", result.getData().getDefaultEmbeddingModel());
        Assertions.assertEquals(DataSize.ofMegabytes(50).toBytes(), result.getData().getUpload().getMaxFileSize());
        Assertions.assertEquals(1, result.getData().getEmbeddingModels().size());
        Assertions.assertEquals("embed-enabled", result.getData().getEmbeddingModels().get(0).getId());
    }

    private AIModelProperties.ModelCandidate model(String id, String provider, String model, boolean enabled) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider(provider);
        candidate.setModel(model);
        candidate.setDimension(1024);
        candidate.setEnabled(enabled);
        return candidate;
    }
}
