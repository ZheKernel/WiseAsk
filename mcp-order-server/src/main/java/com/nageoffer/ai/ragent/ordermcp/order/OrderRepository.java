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

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class OrderRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, order_no, user_id, product_name, quantity, unit_price,
                   total_amount, status, create_time, pay_time
            FROM t_order
            """;

    private static final RowMapper<OrderRecord> ROW_MAPPER = (resultSet, rowNum) -> new OrderRecord(
            resultSet.getString("id"),
            resultSet.getString("order_no"),
            resultSet.getString("user_id"),
            resultSet.getString("product_name"),
            resultSet.getInt("quantity"),
            resultSet.getBigDecimal("unit_price"),
            resultSet.getBigDecimal("total_amount"),
            resultSet.getString("status"),
            resultSet.getTimestamp("create_time").toLocalDateTime(),
            nullableDateTime(resultSet.getTimestamp("pay_time"))
    );

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<OrderRecord> findByUserId(String userId, OrderQueryCriteria criteria) {
        return search(criteria, userId);
    }

    public List<OrderRecord> searchAll(OrderQueryCriteria criteria) {
        return search(criteria, criteria.userId());
    }

    public Optional<OrderRecord> findByOrderNo(String orderNo) {
        return queryOne(SELECT_COLUMNS + """
                WHERE deleted = 0
                  AND order_no = :orderNo
                """, new MapSqlParameterSource("orderNo", orderNo));
    }

    public Optional<OrderRecord> findByOrderNoAndUserId(String orderNo, String userId) {
        return queryOne(SELECT_COLUMNS + """
                WHERE deleted = 0
                  AND order_no = :orderNo
                  AND user_id = :userId
                """, new MapSqlParameterSource()
                .addValue("orderNo", orderNo)
                .addValue("userId", userId));
    }

    private List<OrderRecord> search(OrderQueryCriteria criteria, String userId) {
        StringBuilder sql = new StringBuilder(SELECT_COLUMNS).append("WHERE deleted = 0\n");
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (userId != null) {
            sql.append("  AND user_id = :userId\n");
            parameters.addValue("userId", userId);
        }
        if (criteria.status() != null) {
            sql.append("  AND status = :status\n");
            parameters.addValue("status", criteria.status());
        }
        if (criteria.startDate() != null) {
            sql.append("  AND create_time >= :startTime\n");
            parameters.addValue("startTime", criteria.startDate().atStartOfDay());
        }
        if (criteria.endDate() != null) {
            sql.append("  AND create_time < :endTime\n");
            parameters.addValue("endTime", criteria.endDate().plusDays(1).atStartOfDay());
        }
        sql.append("ORDER BY create_time DESC\nLIMIT :limit");
        parameters.addValue("limit", criteria.limit());
        return jdbcTemplate.query(sql.toString(), parameters, ROW_MAPPER);
    }

    private Optional<OrderRecord> queryOne(String sql, MapSqlParameterSource parameters) {
        List<OrderRecord> records = jdbcTemplate.query(sql, parameters, ROW_MAPPER);
        return records.stream().findFirst();
    }

    private static LocalDateTime nullableDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
