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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 普通登录用户可安全读取的 RAG 能力摘要。
 */
@Data
@Builder
public class RagCapabilitiesVO {

    private UploadCapabilities upload;
    private String defaultEmbeddingModel;
    private List<EmbeddingModelOption> embeddingModels;

    @Data
    @Builder
    public static class UploadCapabilities {
        private Long maxFileSize;
        private Long maxRequestSize;
    }

    @Data
    @Builder
    public static class EmbeddingModelOption {
        private String id;
        private String provider;
        private String model;
        private Integer dimension;
    }
}
