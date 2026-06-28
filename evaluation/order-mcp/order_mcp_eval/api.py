from __future__ import annotations

import json
import threading
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


class ApiError(RuntimeError):
    def __init__(self, message: str, status: int | None = None):
        super().__init__(message)
        self.status = status


def unwrap_result(payload: dict[str, Any]) -> Any:
    code = payload.get("code")
    if str(code) != "0":
        raise ApiError(
            f"Ragent returned code={code}, message={payload.get('message')}"
        )
    return payload.get("data")


class RagentClient:
    def __init__(self, base_url: str, timeout_seconds: float):
        self.base_url = base_url.rstrip("/")
        self.timeout_seconds = timeout_seconds
        self._tokens: dict[str, str] = {}
        self._token_lock = threading.Lock()

    def login(self, username: str, password: str) -> str:
        payload = self._request_json(
            method="POST",
            path="/auth/login",
            body={"username": username, "password": password},
        )
        data = unwrap_result(payload)
        if not isinstance(data, dict) or not data.get("token"):
            raise ApiError(f"Login response for {username} did not contain a token")
        token = str(data["token"])
        with self._token_lock:
            self._tokens[username] = token
        return token

    def evaluate(
        self,
        username: str,
        password: str,
        question: str,
    ) -> tuple[dict[str, Any], float]:
        token = self._get_or_login(username, password)
        try:
            return self._evaluate_with_token(token, question)
        except ApiError as error:
            if error.status != 401:
                raise
            with self._token_lock:
                self._tokens.pop(username, None)
            token = self._get_or_login(username, password)
            return self._evaluate_with_token(token, question)

    def _evaluate_with_token(
        self, token: str, question: str
    ) -> tuple[dict[str, Any], float]:
        start = time.perf_counter()
        payload = self._request_json(
            method="GET",
            path=f"/rag/eval?{urlencode({'question': question})}",
            token=token,
        )
        latency_ms = (time.perf_counter() - start) * 1000
        data = unwrap_result(payload)
        if not isinstance(data, dict):
            raise ApiError("Evaluation response data is not an object")
        return data, latency_ms

    def _get_or_login(self, username: str, password: str) -> str:
        with self._token_lock:
            token = self._tokens.get(username)
        if token:
            return token
        return self.login(username, password)

    def _request_json(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        token: str | None = None,
    ) -> dict[str, Any]:
        encoded_body = (
            json.dumps(body, ensure_ascii=False).encode("utf-8")
            if body is not None
            else None
        )
        headers = {"Accept": "application/json"}
        if encoded_body is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        if token:
            headers["Authorization"] = token
        request = Request(
            url=f"{self.base_url}{path}",
            data=encoded_body,
            headers=headers,
            method=method,
        )
        try:
            with urlopen(request, timeout=self.timeout_seconds) as response:
                raw = response.read().decode("utf-8")
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise ApiError(
                f"HTTP {error.code} calling {path}: {detail[:500]}",
                status=error.code,
            ) from error
        except URLError as error:
            raise ApiError(f"Unable to call {path}: {error.reason}") from error
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ApiError(f"Invalid JSON from {path}: {raw[:500]}") from error
        if not isinstance(payload, dict):
            raise ApiError(f"JSON response from {path} is not an object")
        return payload
