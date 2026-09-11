"""Uninstrumented golden agent.

A small, deterministic multi-step agent with NO Opik instrumentation — no
`@opik.track`, no imports from opik. It exists so the Ollie `/instrument` flow
has a known-shaped target to add tracing to: an entrypoint that fans out to a
retrieval "tool" step and a generation "llm" step, two levels deep.

Deliberately dependency-free and offline (no real LLM call) so it runs anywhere
the E2E suite runs. The point is the call structure Ollie should discover and
wrap, not the model output.
"""


def retrieve(query: str) -> list[str]:
    facts = {
        "capital of france": ["Paris is the capital of France."],
        "tallest mountain": ["Mount Everest is the tallest mountain."],
    }
    return facts.get(query.strip().lower(), ["No relevant facts found."])


def generate(query: str, context: list[str]) -> str:
    joined = " ".join(context)
    return f"Answer to '{query}': {joined}"


def run(query: str) -> str:
    context = retrieve(query)
    return generate(query, context)


if __name__ == "__main__":
    print(run("capital of france"))
