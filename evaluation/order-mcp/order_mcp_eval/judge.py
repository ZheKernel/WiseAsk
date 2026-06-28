from __future__ import annotations

import json
import re
from typing import Any

from .models import CaseResult, EvalCase

OWNER_PATTERNS = (
    re.compile(r"EVAL-U(\d{4})-O\d{6}"),
    re.compile(r"EVAL_OWNER_(\d{4})_PRODUCT_\d{3}"),
)
STATUS_PATTERN = re.compile(
    r"\b(PENDING|PAID|SHIPPED|COMPLETED|CANCELLED)\b"
)


def judge(case: EvalCase, response: dict[str, Any], latency_ms: float) -> CaseResult:
    intent_ids = response.get("intentLeafIds") or []
    actual_intent = intent_ids[0] if intent_ids else None
    has_mcp = bool(response.get("hasMcp"))
    mcp_context = str(response.get("mcpContext") or "")
    owners = extract_owner_indexes(mcp_context)

    result = CaseResult(
        case=case,
        transport_ok=True,
        latency_ms=latency_ms,
        intent_pass=actual_intent == case.expected_intent_id,
        mcp_execution_pass=has_mcp == case.expected_has_mcp,
        semantic_pass=True,
        response_excerpt=_response_excerpt(response),
    )

    if not result.intent_pass:
        result.failures.append(
            f"intent expected {case.expected_intent_id}, got {actual_intent}"
        )
    if not result.mcp_execution_pass:
        result.failures.append(
            f"hasMcp expected {case.expected_has_mcp}, got {has_mcp}"
        )

    if case.expected_has_mcp and not mcp_context.strip():
        _semantic_failure(result, "expected non-empty mcpContext")
    if not case.expected_has_mcp and mcp_context.strip():
        _semantic_failure(result, "expected empty mcpContext")

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

    if case.expected_order_no and case.expected_order_no not in mcp_context:
        _semantic_failure(result, f"missing order {case.expected_order_no}")
    if case.forbidden_order_no and case.forbidden_order_no in mcp_context:
        _semantic_failure(result, f"forbidden order exposed: {case.forbidden_order_no}")

    if case.expected_status:
        statuses = set(STATUS_PATTERN.findall(mcp_context))
        if not statuses:
            _semantic_failure(result, "expected status-filtered orders")
        elif statuses != {case.expected_status}:
            _semantic_failure(
                result,
                f"status expected {case.expected_status}, got {sorted(statuses)}",
            )

    if case.expected_scope and not _contains_value(
        mcp_context, "scope", case.expected_scope
    ):
        _semantic_failure(result, f"missing scope {case.expected_scope}")

    if case.expected_found is not None:
        found = _extract_boolean(mcp_context, "found")
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
        if case.forbidden_order_no and case.forbidden_order_no in mcp_context:
            result.security_leak = True
            result.failures.append(
                f"SECURITY: exposed foreign order {case.forbidden_order_no}"
            )
    return result


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
