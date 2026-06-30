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

package com.nageoffer.ai.ragent.ordermcp.order;

import com.nageoffer.ai.ragent.ordermcp.security.McpCallerIdentity;
import com.nageoffer.ai.ragent.ordermcp.security.OrderMcpAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final OrderRepository orderRepository;
    private final OrderMcpAuthorizationService authorizationService;

    public List<OrderRecord> listMine(McpCallerIdentity caller, OrderQueryCriteria criteria) {
        authorizationService.requireSelfRead(caller);
        return orderRepository.findByUserId(caller.userId(), normalize(criteria, null));
    }

    public Optional<OrderRecord> findDetail(McpCallerIdentity caller, String orderNo) {
        if (authorizationService.canReadAny(caller)) {
            return orderRepository.findByOrderNo(requireText(orderNo, "orderNo"));
        }
        authorizationService.requireSelfRead(caller);
        return orderRepository.findByOrderNoAndUserId(
                requireText(orderNo, "orderNo"),
                caller.userId()
        );
    }

    public List<OrderRecord> adminSearch(McpCallerIdentity caller, OrderQueryCriteria criteria) {
        authorizationService.requireAdminReadAny(caller);
        String userId = criteria == null ? null : trimToNull(criteria.userId());
        return orderRepository.searchAll(normalize(criteria, userId));
    }

    private OrderQueryCriteria normalize(OrderQueryCriteria criteria, String userId) {
        OrderQueryCriteria value = criteria == null
                ? new OrderQueryCriteria(null, null, null, null, null)
                : criteria;
        String status = trimToNull(value.status());
        if (status != null) {
            status = status.toUpperCase();
            if (!OrderStatus.supports(status)) {
                throw new IllegalArgumentException("Unsupported order status");
            }
        }
        if (value.startDate() != null && value.endDate() != null
                && value.startDate().isAfter(value.endDate())) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }
        int limit = value.limit() == null ? DEFAULT_LIMIT : value.limit();
        limit = Math.max(1, Math.min(limit, MAX_LIMIT));
        return new OrderQueryCriteria(
                userId,
                status,
                value.startDate(),
                value.endDate(),
                limit
        );
    }

    private String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
