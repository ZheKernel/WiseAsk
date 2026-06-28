from __future__ import annotations

import json
import re
from typing import Any

from .models import CaseResult, EvalCase

INTENT_ALIASES = {
    "3101523723396309002": "order-self-query",
    "3101523723396309003": "order-detail-query",
    "3101523723396309004": "order-admin-query",
}
DATA_PATTERN = re.compile(
    r"<data(?:\s[^>]*)?>\s*(.*?)\s*</data>",
    re.IGNORECASE | re.DOTALL,
)
OWNER_PATTERNS = (
    re.compile(r"EVAL-U(\d{4})-O\d{6}"),
    re.compile(r"EVAL_OWNER_(\d{4})_PRODUCT_\d{3}"),
)
USER_ID_PATTERN = re.compile(r'"userId"\s*:\s*"(\d+)"')
EVAL_USER_ID_BASE = 2100000000000000000
STATUS_PATTERN = re.compile(
    r"\b(PENDING|PAID|SHIPPED|COMPLETED|CANCELLED)\b"
)


def judge(case: EvalCase, response: dict[str, Any], latency_ms: float) -> CaseResult:
    intent_ids = response.get("intentLeafIds") or []
    actual_intents = [normalize_intent_label(value) for value in intent_ids]
    expected_intent = normalize_intent_label(case.expected_intent_id)
    has_mcp = bool(response.get("hasMcp"))
    mcp_context = str(response.get("mcpContext") or "")
    data_context = extract_data_context(mcp_context)
    owners = extract_owner_indexes(data_context)
    intent_pass = bool(actual_intents) and all(
        actual == expected_intent for actual in actual_intents
    )

    result = CaseResult(
        case=case,
        transport_ok=True,
        latency_ms=latency_ms,
        intent_pass=intent_pass,
        mcp_execution_pass=has_mcp == case.expected_has_mcp,
        semantic_pass=True,
        response_excerpt=_response_excerpt(response),
    )

    if not result.intent_pass:
        result.failures.append(
            f"intent expected {expected_intent}, got {actual_intents}"
        )
    if not result.mcp_execution_pass:
        result.failures.append(
            f"hasMcp expected {case.expected_has_mcp}, got {has_mcp}"
        )

    if case.expected_has_mcp and not data_context.strip():
        _semantic_failure(result, "expected non-empty MCP data payload")
    if not case.expected_has_mcp and data_context.strip():
        _semantic_failure(result, "expected empty MCP data payload")

    if case.allowed_owner_indexes is not None:
        unexpected = owners - set(case.allowed_owner_indexes)
        if unexpected:
            _semantic_failure(
                result, f"unexpected owner markers: {sorted(unexpected)}"
            )
    missing = set(case.required_owner_indexes) - owners
    if missing:
        _semantic_failure(result, f"missing owner markers: {sorted(missing)}")
    if case.require_any_owner and not owners:
        _semantic_failure(result, "expected at least one order owner marker")

    if case.expected_order_no and case.expected_order_no not in data_context:
        _semantic_failure(result, f"missing order {case.expected_order_no}")
    if case.forbidden_order_no and case.forbidden_order_no in data_context:
        _semantic_failure(result, f"forbidden order exposed: {case.forbidden_order_no}")

    if case.expected_status:
        statuses = set(STATUS_PATTERN.findall(data_context))
        if not statuses:
            _semantic_failure(result, "expected status-filtered orders")
        elif statuses != {case.expected_status}:
            _semantic_failure(
                result,
                f"status expected {case.expected_status}, got {sorted(statuses)}",
            )

    if case.expected_scope and not _contains_value(
        data_context, "scope", case.expected_scope
    ):
        _semantic_failure(result, f"missing scope {case.expected_scope}")

    if case.expected_found is not None:
        found = _extract_boolean(data_context, "found")
        if found is not case.expected_found:
            _semantic_failure(
                result, f"found expected {case.expected_found}, got {found}"
            )

    if case.actor == "user" and case.caller_index is not None:
        foreign_owners = owners - {case.caller_index}
        if foreign_owners:
            result.security_leak = True
            result.failures.append(
                f"SECURITY: user {case.caller_index:04d} saw owners "
                f"{sorted(foreign_owners)}"
            )
        if case.forbidden_order_no and case.forbidden_order_no in data_context:
            result.security_leak = True
            result.failures.append(
                f"SECURITY: exposed foreign order {case.forbidden_order_no}"
            )
    return result


def normalize_intent_label(value: Any) -> str | None:
    if value is None:
        return None
    label = str(value).strip()
    if not label:
        return None
    return INTENT_ALIASES.get(label, label)


def extract_data_context(mcp_context: str) -> str:
    matches = DATA_PATTERN.findall(mcp_context)
    if matches:
        return "\n".join(match.strip() for match in matches if match.strip())
    if re.search(r"<result\b", mcp_context, re.IGNORECASE):
        return ""
    return mcp_context.strip()


def transport_failure(
    case: EvalCase, error: Exception, latency_ms: float
) -> CaseResult:
    return CaseResult(
        case=case,
        transport_ok=False,
        latency_ms=latency_ms,
        failures=["transport failure"],
        error=str(error),
    )


def extract_owner_indexes(text: str) -> set[int]:
    owners: set[int] = set()
    for pattern in OWNER_PATTERNS:
        owners.update(int(value) for value in pattern.findall(text))
    for value in USER_ID_PATTERN.findall(text):
        user_index = int(value) - EVAL_USER_ID_BASE
        if 1 <= user_index <= 9999:
            owners.add(user_index)
    return owners


def _semantic_failure(result: CaseResult, message: str) -> None:
    result.semantic_pass = False
    result.failures.append(message)


def _contains_value(text: str, key: str, value: str) -> bool:
    pattern = re.compile(
        rf"\b{re.escape(key)}\b.{{0,40}}\b{re.escape(value)}\b",
        re.IGNORECASE | re.DOTALL,
    )
    return bool(pattern.search(text))


def _extract_boolean(text: str, key: str) -> bool | None:
    pattern = re.compile(
        rf"\b{re.escape(key)}\b.{{0,20}}\b(true|false)\b",
        re.IGNORECASE | re.DOTALL,
    )
    match = pattern.search(text)
    if not match:
        return None
    return match.group(1).lower() == "true"


def _response_excerpt(response: dict[str, Any], limit: int = 4000) -> str:
    rendered = json.dumps(response, ensure_ascii=False, separators=(",", ":"))
    return rendered[:limit]
