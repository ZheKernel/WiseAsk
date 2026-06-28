from __future__ import annotations

import json
import math
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .models import CaseResult


def build_summary(
    results: list[CaseResult],
    wall_seconds: float,
    thresholds: dict[str, Any],
) -> dict[str, Any]:
    total = len(results)
    if total == 0:
        raise ValueError("Cannot summarize an empty result set")
    latencies = sorted(
        result.latency_ms for result in results if result.transport_ok
    )
    scenarios: dict[str, dict[str, int]] = defaultdict(
        lambda: {"total": 0, "passed": 0, "security_leaks": 0}
    )
    for result in results:
        row = scenarios[result.case.scenario]
        row["total"] += 1
        row["passed"] += int(result.passed)
        row["security_leaks"] += int(result.security_leak)

    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "total_requests": total,
        "passed_requests": sum(result.passed for result in results),
        "intent_accuracy": _ratio(
            sum(result.intent_pass for result in results), total
        ),
        "mcp_execution_accuracy": _ratio(
            sum(result.mcp_execution_pass for result in results), total
        ),
        "semantic_accuracy": _ratio(
            sum(result.semantic_pass for result in results), total
        ),
        "transport_error_rate": _ratio(
            sum(not result.transport_ok for result in results), total
        ),
        "security_leak_count": sum(
            result.security_leak for result in results
        ),
        "latency_ms": {
            "p50": _percentile(latencies, 50),
            "p95": _percentile(latencies, 95),
            "p99": _percentile(latencies, 99),
            "max": round(max(latencies), 3) if latencies else None,
        },
        "wall_seconds": round(wall_seconds, 3),
        "throughput_rps": round(total / wall_seconds, 3)
        if wall_seconds > 0
        else None,
        "scenarios": dict(sorted(scenarios.items())),
        "thresholds": thresholds,
    }
    checks = {
        "intent_accuracy": summary["intent_accuracy"]
        >= float(thresholds["intent_accuracy"]),
        "mcp_execution_accuracy": summary["mcp_execution_accuracy"]
        >= float(thresholds["mcp_execution_accuracy"]),
        "semantic_accuracy": summary["semantic_accuracy"]
        >= float(thresholds["semantic_accuracy"]),
        "transport_error_rate": summary["transport_error_rate"]
        <= float(thresholds["max_transport_error_rate"]),
        "security_leak_count": summary["security_leak_count"]
        <= int(thresholds["max_security_leaks"]),
    }
    summary["checks"] = checks
    summary["passed"] = all(checks.values())
    return summary


def write_reports(
    results: list[CaseResult],
    summary: dict[str, Any],
    report_directory: Path,
) -> Path:
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    output = report_directory / run_id
    output.mkdir(parents=True, exist_ok=False)

    with (output / "results.jsonl").open("w", encoding="utf-8") as target:
        for result in results:
            target.write(json.dumps(result.to_dict(), ensure_ascii=False) + "\n")
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output / "summary.md").write_text(
        _render_markdown(summary),
        encoding="utf-8",
    )
    return output


def _ratio(numerator: int, denominator: int) -> float:
    return round(numerator / denominator, 6) if denominator else 0.0


def _percentile(values: list[float], percentile: int) -> float | None:
    if not values:
        return None
    rank = max(0, math.ceil(percentile / 100 * len(values)) - 1)
    return round(values[rank], 3)


def _render_markdown(summary: dict[str, Any]) -> str:
    latency = summary["latency_ms"]
    lines = [
        "# Order MCP Chat Evaluation",
        "",
        f"- Passed: `{summary['passed']}`",
        f"- Requests: `{summary['total_requests']}`",
        f"- Intent accuracy: `{summary['intent_accuracy']:.2%}`",
        f"- MCP execution accuracy: `{summary['mcp_execution_accuracy']:.2%}`",
        f"- Semantic accuracy: `{summary['semantic_accuracy']:.2%}`",
        f"- Transport error rate: `{summary['transport_error_rate']:.2%}`",
        f"- Security leaks: `{summary['security_leak_count']}`",
        f"- Latency p50/p95/p99: `{latency['p50']}` / `{latency['p95']}` / `{latency['p99']}` ms",
        f"- Throughput: `{summary['throughput_rps']}` requests/s",
        "",
        "## Scenarios",
        "",
        "| Scenario | Total | Passed | Security leaks |",
        "| --- | ---: | ---: | ---: |",
    ]
    for name, data in summary["scenarios"].items():
        lines.append(
            f"| {name} | {data['total']} | {data['passed']} | "
            f"{data['security_leaks']} |"
        )
    lines.extend(["", "## Checks", ""])
    for name, passed in summary["checks"].items():
        lines.append(f"- `{name}`: `{passed}`")
    return "\n".join(lines) + "\n"
