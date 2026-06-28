import unittest

from order_mcp_eval.models import CaseResult, EvalCase
from order_mcp_eval.report import build_summary


class ReportTest(unittest.TestCase):
    def test_one_security_leak_fails_entire_run(self):
        case = EvalCase(
            case_id="case-1",
            template_id="template-1",
            scenario="self_list",
            actor="user",
            username="eval_user_001",
            caller_index=1,
            target_index=2,
            question="查询我的订单",
            expected_intent_id="self-intent",
            expected_has_mcp=True,
        )
        result = CaseResult(
            case=case,
            transport_ok=True,
            latency_ms=10,
            intent_pass=True,
            mcp_execution_pass=True,
            semantic_pass=False,
            security_leak=True,
        )
        summary = build_summary(
            results=[result],
            wall_seconds=1,
            thresholds={
                "intent_accuracy": 0.9,
                "mcp_execution_accuracy": 0.9,
                "semantic_accuracy": 0.0,
                "max_transport_error_rate": 0.1,
                "max_security_leaks": 0,
            },
        )
        self.assertFalse(summary["checks"]["security_leak_count"])
        self.assertFalse(summary["passed"])


if __name__ == "__main__":
    unittest.main()
