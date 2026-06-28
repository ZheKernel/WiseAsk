import unittest

from order_mcp_eval.dataset import (
    generate_cases,
    order_no,
    status_for_order,
    user_id,
)
from order_mcp_eval.models import CaseTemplate


class DatasetTest(unittest.TestCase):
    def test_deterministic_identifiers(self):
        self.assertEqual("2100000000000000001", user_id(1))
        self.assertEqual("EVAL-U0007-O000042", order_no(7, 42))
        self.assertEqual("PAID", status_for_order(1))
        self.assertEqual("PENDING", status_for_order(5))

    def test_foreign_detail_uses_different_owner(self):
        template = CaseTemplate(
            template_id="foreign",
            scenario="foreign_detail",
            actor="user",
            weight=1,
            question="查询 {foreign_order_no}",
            expected_intent_id="detail",
            expected_has_mcp=True,
        )
        case = generate_cases(
            templates=[template],
            request_count=1,
            user_count=10,
            orders_per_user=100,
            username_prefix="eval_user_",
            admin_username="eval_admin",
            seed=7,
        )[0]
        self.assertNotEqual(case.caller_index, case.target_index)
        self.assertIn(f"EVAL-U{case.target_index:04d}", case.question)
        self.assertEqual((), case.allowed_owner_indexes)


if __name__ == "__main__":
    unittest.main()
