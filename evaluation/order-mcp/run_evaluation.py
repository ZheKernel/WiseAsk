from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from order_mcp_eval.runner import run


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Evaluate Ragent Chat intent routing and Order MCP permissions"
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=Path(__file__).with_name("config.example.json"),
    )
    parser.add_argument("--requests", type=int)
    parser.add_argument("--concurrency", type=int)
    parser.add_argument("--seed", type=int)
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    summary, output = run(
        config_path=args.config,
        request_count=args.requests,
        concurrency=args.concurrency,
        seed=args.seed,
        dry_run=args.dry_run,
    )
    if summary is None:
        print("Dry run completed; no HTTP requests were sent.")
        return 0
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"Reports: {output}")
    return 0 if summary["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
