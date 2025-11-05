from opik_optimizer.utils import search_wikipedia  # noqa: E402


def search_wikipedia_tool(query: str) -> list[str]:
    return search_wikipedia(query, use_api=True)
