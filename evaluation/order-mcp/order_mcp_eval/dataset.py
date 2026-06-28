from __future__ import annotations

import json
import random
from collections import Counter
from pathlib import Path

from .models import CaseTemplate, EvalCase

STATUS_NAMES = {
    "PENDING": "待支付",
    "PAID": "已支付",
    "SHIPPED": "已发货",
    "COMPLETED": "已完成",
    "CANCELLED": "已取消",
}


def user_id(user_index: int) -> str:
    return str(2100000000000000000 + user_index)


def username(prefix: str, user_index: int) -> str:
    return f"{prefix}{user_index:03d}"


def order_no(user_index: int, order_index: int) -> str:
    return f"EVAL-U{user_index:04d}-O{order_index:06d}"


def status_for_order(order_index: int) -> str:
    return {
        0: "PENDING",
        1: "PAID",
        2: "SHIPPED",
        3: "COMPLETED",
        4: "CANCELLED",
    }[order_index % 5]


def load_templates(path: Path) -> list[CaseTemplate]:
    templates: list[CaseTemplate] = []
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            raw = json.loads(line)
            try:
                templates.append(
                    CaseTemplate(
                        template_id=raw["id"],
                        scenario=raw["scenario"],
                        actor=raw["actor"],
                        weight=int(raw.get("weight", 1)),
                        question=raw["question"],
                        expected_intent_id=raw["expected_intent_id"],
                        expected_has_mcp=bool(raw["expected_has_mcp"]),
                    )
                )
            except KeyError as error:
                raise ValueError(
                    f"Dataset line {line_number} is missing field {error.args[0]}"
                ) from error
    if not templates:
        raise ValueError(f"No evaluation templates found in {path}")
    return templates


def generate_cases(
    templates: list[CaseTemplate],
    request_count: int,
    user_count: int,
    orders_per_user: int,
    username_prefix: str,
    admin_username: str,
    seed: int,
) -> list[EvalCase]:
    if request_count <= 0:
        raise ValueError("request_count must be positive")
    if user_count < 2:
        raise ValueError("user_count must be at least 2")
    if orders_per_user <= 0:
        raise ValueError("orders_per_user must be positive")

    rng = random.Random(seed)
    weights = [template.weight for template in templates]
    selected = rng.choices(templates, weights=weights, k=request_count)
    return [
        _materialize_case(
            template=template,
            sequence=sequence,
            rng=rng,
            user_count=user_count,
            orders_per_user=orders_per_user,
            username_prefix=username_prefix,
            admin_username=admin_username,
        )
        for sequence, template in enumerate(selected, start=1)
    ]


def scenario_counts(cases: list[EvalCase]) -> dict[str, int]:
    return dict(sorted(Counter(case.scenario for case in cases).items()))


def _materialize_case(
    template: CaseTemplate,
    sequence: int,
    rng: random.Random,
    user_count: int,
    orders_per_user: int,
    username_prefix: str,
    admin_username: str,
) -> EvalCase:
    caller_index = rng.randint(1, user_count) if template.actor == "user" else None
    target_index = rng.randint(1, user_count)
    if caller_index is not None:
        while target_index == caller_index:
            target_index = rng.randint(1, user_count)

    own_order_index = rng.randint(1, orders_per_user)
    foreign_order_index = rng.randint(1, orders_per_user)
    target_order_index = rng.randint(1, orders_per_user)
    status = rng.choice(list(STATUS_NAMES))
    limit = rng.randint(3, 10)

    own_order = (
        order_no(caller_index, own_order_index)
        if caller_index is not None
        else order_no(target_index, target_order_index)
    )
    foreign_order = order_no(target_index, foreign_order_index)
    target_order = order_no(target_index, target_order_index)
    question = template.question.format(
        caller_user_id=user_id(caller_index) if caller_index is not None else "",
        target_user_id=user_id(target_index),
        own_order_no=own_order,
        foreign_order_no=foreign_order,
        target_order_no=target_order,
        status=status,
        status_cn=STATUS_NAMES[status],
        limit=limit,
    )

    common = {
        "case_id": f"case-{sequence:06d}",
        "template_id": template.template_id,
        "scenario": template.scenario,
        "actor": template.actor,
        "username": (
            username(username_prefix, caller_index)
            if caller_index is not None
            else admin_username
        ),
        "caller_index": caller_index,
        "target_index": target_index,
        "question": question,
        "expected_intent_id": template.expected_intent_id,
        "expected_has_mcp": template.expected_has_mcp,
    }

    if template.scenario == "self_list":
        return EvalCase(
            **common,
            allowed_owner_indexes=(caller_index,),
            required_owner_indexes=(caller_index,),
            expected_scope="SELF",
            require_any_owner=True,
        )
    if template.scenario == "self_status":
        return EvalCase(
            **common,
            allowed_owner_indexes=(caller_index,),
            required_owner_indexes=(caller_index,),
            expected_status=status,
            expected_scope="SELF",
            require_any_owner=True,
        )
    if template.scenario == "own_detail":
        return EvalCase(
            **common,
            allowed_owner_indexes=(caller_index,),
            required_owner_indexes=(caller_index,),
            expected_order_no=own_order,
            expected_found=True,
        )
    if template.scenario == "foreign_detail":
        return EvalCase(
            **common,
            allowed_owner_indexes=(),
            forbidden_order_no=foreign_order,
            expected_found=False,
        )
    if template.scenario == "user_admin_search":
        return EvalCase(**common, allowed_owner_indexes=())
    if template.scenario == "admin_target":
        return EvalCase(
            **common,
            allowed_owner_indexes=(target_index,),
            required_owner_indexes=(target_index,),
            expected_scope="ADMIN",
            require_any_owner=True,
        )
    if template.scenario == "admin_all":
        return EvalCase(
            **common,
            allowed_owner_indexes=None,
            expected_scope="ADMIN",
            require_any_owner=True,
        )
    raise ValueError(f"Unsupported scenario: {template.scenario}")
