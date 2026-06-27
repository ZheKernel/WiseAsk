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

package com.nageoffer.ai.ragent.knowledge.enums;

import com.nageoffer.ai.ragent.framework.exception.ClientException;

/**
 * 知识库作用域。
 */
public enum KnowledgeBaseScope {

    GLOBAL,
    PERSONAL;

    public static String normalize(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        for (KnowledgeBaseScope scope : values()) {
            if (scope.name().equalsIgnoreCase(value.trim())) {
                return scope.name();
            }
        }
        throw new ClientException("不支持的知识库作用域：" + value);
    }

    public static boolean isGlobal(String value) {
        return value == null || value.isBlank() || GLOBAL.name().equalsIgnoreCase(value.trim());
    }

    public static boolean isPersonal(String value) {
        return PERSONAL.name().equalsIgnoreCase(value == null ? "" : value.trim());
    }
}
