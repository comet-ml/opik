"""Playground for the TypeSafe AI (Jev) integration.

Each run makes exactly one API call (the early-access key has a small budget).

    python typesafe_playground.py
    python typesafe_playground.py --state "Love the product, but the invoice is wrong" --async
    python typesafe_playground.py --plain-http      # bypasses typesafe-sdk, uses @track + update_current_span
    python typesafe_playground.py --error           # invalid key -> error_info on the span, no call spent

The key is read from TYPESAFE_API_KEY, falling back to tests/pytest.ini.
"""

import argparse
import asyncio
import os
import pathlib
import re

import httpx
from typesafe_sdk import (
    AsyncTypeSafeClient,
    Choice,
    Noul,
    RetryPolicy,
    Score,
    TypeSafeClient,
)

import opik
from opik import opik_context, track
from opik.integrations.typesafe import track_typesafe

os.environ.setdefault("OPIK_PROJECT_NAME", "typesafe-playground")

QUESTIONS = {
    "category": Choice(
        instructions="What is this ticket about?",
        criteria={"billing": None, "technical": None, "praise": None, "other": None},
    ),
    "is_urgent": Noul(instructions="The message conveys urgency or time pressure"),
    "frustration": Score(
        instructions="How frustrated is the customer?",
        criteria=["calm", "annoyed", "furious"],
    ),
}


def _api_key(error_mode: bool) -> str:
    if error_mode:
        return "invalid-api-key"
    if os.environ.get("TYPESAFE_API_KEY", "").strip():
        return os.environ["TYPESAFE_API_KEY"].strip()
    ini = pathlib.Path(__file__).parent / "tests" / "pytest.ini"
    match = re.search(r"^\s*TYPESAFE_API_KEY=(\S+)", ini.read_text(), re.M)
    if not match:
        raise SystemExit("Set TYPESAFE_API_KEY or add it to tests/pytest.ini")
    return match.group(1)


def _print_answers(answers) -> None:
    for name, answer in answers.items():
        answer = answer if isinstance(answer, dict) else answer.model_dump()
        print(f"  {name:12s} {answer}")


@track
def triage_ticket(client: TypeSafeClient, ticket: str, model: str | None):
    response = client.system_one(
        state={"document": ticket}, questions=QUESTIONS, model=model
    )
    return {
        "category": response.choices["category"].choice,
        "is_urgent": response.nouls["is_urgent"].noul > 0.5,
        "frustration": round(response.scores["frustration"].score, 2),
    }


@track
async def triage_ticket_async(
    client: AsyncTypeSafeClient, ticket: str, model: str | None
):
    async with client:
        response = await client.system_one(
            state={"document": ticket}, questions=QUESTIONS, model=model
        )
    return {
        "category": response.choices["category"].choice,
        "is_urgent": response.nouls["is_urgent"].noul > 0.5,
        "frustration": round(response.scores["frustration"].score, 2),
    }


@track(type="llm", name="system_one")
def system_one_over_http(api_key: str, state: str, questions: dict, model: str) -> dict:
    """The docs' 'plain HTTP' recipe: no typesafe-sdk client involved."""
    response = httpx.post(
        "https://api.typesafe.ai/v1/systemone",
        headers={"Authorization": f"Bearer {api_key}"},
        json={"state": state, "model": model, "questions": questions},
        timeout=15,
    )
    response.raise_for_status()
    body = response.json()

    usage = body.get("usage") or {}
    opik_context.update_current_span(
        provider="typesafe",
        model=body.get("model", model),
        usage={
            "prompt_tokens": usage.get("input_tokens") or 0,
            "completion_tokens": usage.get("output_tokens") or 0,
            "total_tokens": (usage.get("input_tokens") or 0)
            + (usage.get("output_tokens") or 0),
        },
    )
    return body["answers"]


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--state", default="I was charged twice. Please fix this ASAP.")
    parser.add_argument(
        "--model", default=None, help="defaults to the SDK default (jev-latest)"
    )
    parser.add_argument(
        "--async", dest="use_async", action="store_true", help="use AsyncTypeSafeClient"
    )
    parser.add_argument(
        "--plain-http", action="store_true", help="call the REST API directly"
    )
    parser.add_argument(
        "--error", action="store_true", help="use an invalid key to see error_info"
    )
    args = parser.parse_args()

    api_key = _api_key(args.error)
    retry = RetryPolicy(max_retries=0)

    print(f"state: {args.state!r}")
    try:
        if args.plain_http:
            questions = {name: q.model_dump() for name, q in QUESTIONS.items()}
            answers = system_one_over_http(
                api_key, args.state, questions, args.model or "jev-latest"
            )
            print("answers (plain HTTP):")
            _print_answers(answers)
        elif args.use_async:
            client = track_typesafe(
                AsyncTypeSafeClient(api_key=api_key, retry=retry, timeout=20)
            )
            print(
                "result (async):",
                asyncio.run(triage_ticket_async(client, args.state, args.model)),
            )
        else:
            client = track_typesafe(
                TypeSafeClient(api_key=api_key, retry=retry, timeout=20)
            )
            with client:
                print("result (sync):", triage_ticket(client, args.state, args.model))
    except Exception as exc:
        print(f"call failed as {type(exc).__name__}: {exc}")
        print("the span was still logged with error_info")
    finally:
        opik.flush_tracker()


if __name__ == "__main__":
    main()
