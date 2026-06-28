import unittest

from order_mcp_eval.api import ApiError, unwrap_result


class ApiTest(unittest.TestCase):
    def test_unwrap_result_accepts_string_zero(self):
        self.assertEqual({"value": 1}, unwrap_result({"code": "0", "data": {"value": 1}}))

    def test_unwrap_result_rejects_error_code(self):
        with self.assertRaises(ApiError):
            unwrap_result({"code": "A00001", "message": "failed"})


if __name__ == "__main__":
    unittest.main()
