from __future__ import annotations

import json
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

from .api import RagentClient
from .dataset import generate_cases, load_templates, scenario_counts
from .judge import judge, transport_failure
from .models import CaseResult, EvalCase
from .report import build_summary, write_reports


def load_config(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as source:
        config = json.load(source)
    required = (
        "base_url",
        "timeout_seconds",
        "request_count",
        "concurrency",
        "seed",
        "user_count",
        "orders_per_user",
        "username_prefix",
        "user_password",
        "admin_username",
        "admin_password",
        "dataset_path",
        "report_directory",
        "thresholds",
    )
    missing = [key for key in required if key not in config]
    if missing:
        raise ValueError(f"Missing config fields: {', '.join(missing)}")
    return config


def run(
    config_path: Path,
    request_count: int | None = None,
    concurrency: int | None = None,
    seed: int | None = None,
    dry_run: bool = False,
) -> tuple[dict[str, Any] | None, Path | None]:
    config_path = config_path.resolve()
    project_dir = config_path.parent
    config = load_config(config_path)
    if request_count is not None:
        config["request_count"] = request_count
    if concurrency is not None:
        config["concurrency"] = concurrency
    if seed is not None:
        config["seed"] = seed

    dataset_path = _resolve_path(project_dir, config["dataset_path"])
    templates = load_templates(dataset_path)
    cases = generate_cases(
        templates=templates,
        request_count=int(config["request_count"]),
        user_count=int(config["user_count"]),
        orders_per_user=int(config["orders_per_user"]),
        username_prefix=str(config["username_prefix"]),
        admin_username=str(config["admin_username"]),
        seed=int(config["seed"]),
    )

    print(
        json.dumps(
            {
                "request_count": len(cases),
                "concurrency": int(config["concurrency"]),
                "seed": int(config["seed"]),
                "scenarios": scenario_counts(cases),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    if dry_run:
        for case in cases[:5]:
            print(f"{case.case_id} [{case.username}] {case.question}")
        return None, None

    client = RagentClient(
        base_url=str(config["base_url"]),
        timeout_seconds=float(config["timeout_seconds"]),
    )
    start = time.perf_counter()
    results = _execute_cases(
        client=client,
        cases=cases,
        concurrency=int(config["concurrency"]),
        user_password=str(config["user_password"]),
        admin_username=str(config["admin_username"]),
        admin_password=str(config["admin_password"]),
    )
    wall_seconds = time.perf_counter() - start
    summary = build_summary(
        results=results,
        wall_seconds=wall_seconds,
        thresholds=config["thresholds"],
    )
    report_dir = _resolve_path(project_dir, config["report_directory"])
    output = write_reports(results, summary, report_dir)
    return summary, output


def _execute_cases(
    client: RagentClient,
    cases: list[EvalCase],
    concurrency: int,
    user_password: str,
    admin_username: str,
    admin_password: str,
) -> list[CaseResult]:
    if concurrency <= 0:
        raise ValueError("concurrency must be positive")

    def execute(case: EvalCase) -> CaseResult:
        password = (
            admin_password if case.username == admin_username else user_password
        )
        started = time.perf_counter()
        try:
            response, latency_ms = client.evaluate(
                username=case.username,
                password=password,
                question=case.question,
            )
            return judge(case, response, latency_ms)
        except Exception as error:
            latency_ms = (time.perf_counter() - started) * 1000
            return transport_failure(case, error, latency_ms)

    ordered: list[CaseResult | None] = [None] * len(cases)
    completed = 0
    progress_interval = max(1, len(cases) // 20)
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {
            executor.submit(execute, case): index
            for index, case in enumerate(cases)
        }
        for future in as_completed(futures):
            ordered[futures[future]] = future.result()
            completed += 1
            if completed % progress_interval == 0 or completed == len(cases):
                print(f"Progress: {completed}/{len(cases)}")
    return [result for result in ordered if result is not None]


def _resolve_path(base: Path, value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else base / path
