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

package com.nageoffer.ai.ragent.rag.core.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.enums.KnowledgeBaseScope;
import com.nageoffer.ai.ragent.rag.config.McpToolPermissionProperties;
import com.nageoffer.ai.ragent.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 默认 RAG 资源权限实现。
 */
@Service
@RequiredArgsConstructor
public class DefaultRagResourcePermissionService implements RagResourcePermissionService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final McpToolPermissionProperties mcpToolPermissionProperties;

    @Override
    public List<String> listRetrievableCollections(LoginUser user) {
        if (user == null || StrUtil.isBlank(user.getUserId())) {
            return List.of();
        }
        List<KnowledgeBaseDO> records = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );
        if (CollUtil.isEmpty(records)) {
            return List.of();
        }
        return records.stream()
                .filter(record -> canRetrieveKnowledgeBase(user, record))
                .map(KnowledgeBaseDO::getCollectionName)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    @Override
    public boolean canViewKnowledgeBase(LoginUser user, KnowledgeBaseDO knowledgeBase) {
        if (!isActive(knowledgeBase) || user == null || StrUtil.isBlank(user.getUserId())) {
            return false;
        }
        return isAdmin(user)
                || KnowledgeBaseScope.isGlobal(knowledgeBase.getScope())
                || isOwnedPersonalKnowledgeBase(user, knowledgeBase);
    }

    @Override
    public boolean canManageKnowledgeBase(LoginUser user, KnowledgeBaseDO knowledgeBase) {
        if (!isActive(knowledgeBase) || user == null || StrUtil.isBlank(user.getUserId())) {
            return false;
        }
        return isAdmin(user) || isOwnedPersonalKnowledgeBase(user, knowledgeBase);
    }

    @Override
    public boolean canCallMcpTool(LoginUser user, String toolId) {
        if (user == null || StrUtil.isBlank(user.getUserId()) || StrUtil.isBlank(toolId)) {
            return false;
        }
        if (containsIgnoreCase(mcpToolPermissionProperties.getDisabledToolIds(), toolId)) {
            return false;
        }
        if (containsIgnoreCase(mcpToolPermissionProperties.getAdminOnlyToolIds(), toolId)) {
            return isAdmin(user);
        }
        return true;
    }

    private boolean isOwnedPersonalKnowledgeBase(LoginUser user, KnowledgeBaseDO knowledgeBase) {
        return KnowledgeBaseScope.isPersonal(knowledgeBase.getScope())
                && Objects.equals(user.getUserId(), knowledgeBase.getOwnerUserId());
    }

    private boolean canRetrieveKnowledgeBase(LoginUser user, KnowledgeBaseDO knowledgeBase) {
        return isActive(knowledgeBase)
                && user != null
                && StrUtil.isNotBlank(user.getUserId())
                && (KnowledgeBaseScope.isGlobal(knowledgeBase.getScope())
                || isOwnedPersonalKnowledgeBase(user, knowledgeBase));
    }

    private boolean isActive(KnowledgeBaseDO knowledgeBase) {
        return knowledgeBase != null && !Objects.equals(knowledgeBase.getDeleted(), 1);
    }

    private boolean isAdmin(LoginUser user) {
        return user != null && UserRole.ADMIN.getCode().equalsIgnoreCase(user.getRole());
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        if (CollUtil.isEmpty(values)) {
            return false;
        }
        return values.stream().filter(Objects::nonNull).anyMatch(item -> item.equalsIgnoreCase(target));
    }
}
