from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass(frozen=True)
class CaseTemplate:
    template_id: str
    scenario: str
    actor: str
    weight: int
    question: str
    expected_intent_id: str
    expected_has_mcp: bool


@dataclass(frozen=True)
class EvalCase:
    case_id: str
    template_id: str
    scenario: str
    actor: str
    username: str
    caller_index: int | None
    target_index: int | None
    question: str
    expected_intent_id: str
    expected_has_mcp: bool
    allowed_owner_indexes: tuple[int, ...] | None = ()
    required_owner_indexes: tuple[int, ...] = ()
    expected_order_no: str | None = None
    forbidden_order_no: str | None = None
    expected_status: str | None = None
    expected_scope: str | None = None
    expected_found: bool | None = None
    require_any_owner: bool = False


@dataclass
class CaseResult:
    case: EvalCase
    transport_ok: bool
    latency_ms: float
    intent_pass: bool = False
    mcp_execution_pass: bool = False
    semantic_pass: bool = False
    security_leak: bool = False
    failures: list[str] = field(default_factory=list)
    response_excerpt: str = ""
    error: str | None = None

    @property
    def passed(self) -> bool:
        return (
            self.transport_ok
            and self.intent_pass
            and self.mcp_execution_pass
            and self.semantic_pass
            and not self.security_leak
        )

    def to_dict(self) -> dict[str, Any]:
        case_data = asdict(self.case)
        return {
            **case_data,
            "transport_ok": self.transport_ok,
            "latency_ms": round(self.latency_ms, 3),
            "intent_pass": self.intent_pass,
            "mcp_execution_pass": self.mcp_execution_pass,
            "semantic_pass": self.semantic_pass,
            "security_leak": self.security_leak,
            "passed": self.passed,
            "failures": self.failures,
            "response_excerpt": self.response_excerpt,
            "error": self.error,
        }
