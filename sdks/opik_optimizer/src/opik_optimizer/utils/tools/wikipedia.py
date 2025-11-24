"""
Wikipedia search tools with multiple backends (API, ColBERT, BM25).

This module provides unified Wikipedia search with support for:
- Wikipedia API (default, fast, always available)
- ColBERTv2 (neural search, requires remote server)
- BM25 (local retrieval, requires index)

Example:
    ```python
    from opik_optimizer.utils.tools.wikipedia import search_wikipedia

    # Wikipedia API (default)
    results = search_wikipedia("What is Python?")

    # ColBERT neural search
    results = search_wikipedia("What is Python?", search_type="colbert")

    # BM25 search with HuggingFace index
    results = search_wikipedia(
        "What is Python?",
        search_type="bm25",
        bm25_hf_repo="Comet/wikipedia-2017-bm25"
    )

    # BM25 search with local index
    results = search_wikipedia(
        "What is Python?",
        search_type="bm25",
        bm25_index_dir="./wiki17_abstracts"
    )
    ```
"""

import logging
import re
from pathlib import Path
from typing import Any, Literal, cast

import requests

logger = logging.getLogger(__name__)


def search_wikipedia(
    query: str,
    search_type: Literal["api", "colbert", "bm25"] = "api",
    n: int = 3,
    bm25_index_dir: str | None = None,
    bm25_hf_repo: str | None = None,
    use_api: bool | None = None,  # Backward compatibility
) -> list[str]:
    """
    Search Wikipedia using different backends.

    Args:
        query: The search query string
        search_type: Search backend to use:
            - "api": Wikipedia API (default, fast, always available)
            - "colbert": ColBERTv2 neural search (requires remote server)
            - "bm25": BM25 local retrieval (requires index)
        n: Number of results to return (default: 3)
        bm25_index_dir: Path to local BM25 index (only for search_type="bm25")
        bm25_hf_repo: HuggingFace repo for BM25 index (only for search_type="bm25")
        use_api: DEPRECATED. Use search_type parameter instead.
            - use_api=True → search_type="api"
            - use_api=False → search_type="colbert"

    Returns:
        List of search result strings

    Example:
        # API search (default)
        results = search_wikipedia("What is Python?")

        # ColBERT search
        results = search_wikipedia("What is Python?", search_type="colbert")

        # BM25 search from HuggingFace
        results = search_wikipedia(
            "What is Python?",
            search_type="bm25",
            bm25_hf_repo="Comet/wikipedia-2017-bm25"
        )

        # BM25 search from local index
        results = search_wikipedia(
            "What is Python?",
            search_type="bm25",
            bm25_index_dir="./wiki17_abstracts"
        )
    """
    # Handle backward compatibility with use_api parameter
    if use_api is not None:
        import warnings

        warnings.warn(
            "use_api parameter is deprecated. Use search_type='api' or search_type='colbert' instead.",
            DeprecationWarning,
            stacklevel=2,
        )
        search_type = "api" if use_api else "colbert"

    # Route to appropriate search backend
    if search_type == "api":
        return _search_wikipedia_api(query, max_results=n)
    elif search_type == "colbert":
        return _search_wikipedia_colbert(query, k=n)
    elif search_type == "bm25":
        return _search_wikipedia_bm25(
            query, n=n, index_dir=bm25_index_dir, hf_repo=bm25_hf_repo
        )
    else:
        raise ValueError(
            f"Invalid search_type: {search_type}. Must be 'api', 'colbert', or 'bm25'"
        )


def _search_wikipedia_api(query: str, max_results: int = 3) -> list[str]:
    """
    Search Wikipedia using the Wikipedia API.

    Args:
        query: Search query
        max_results: Maximum number of results

    Returns:
        List of formatted results
    """
    try:
        # Search for pages using the search API
        search_params: dict[str, str | int] = {
            "action": "query",
            "format": "json",
            "list": "search",
            "srsearch": query,
            "srlimit": max_results,
            "srprop": "snippet",
        }

        headers = {
            "User-Agent": "OpikOptimizer/1.0 (https://github.com/opik-ai/opik-optimizer)"
        }
        search_response = requests.get(
            "https://en.wikipedia.org/w/api.php",
            params=search_params,
            headers=headers,
            timeout=5,
        )

        if search_response.status_code != 200:
            raise Exception(f"Search API returned status {search_response.status_code}")

        search_data = search_response.json()

        results = []
        if "query" in search_data and "search" in search_data["query"]:
            for item in search_data["query"]["search"][:max_results]:
                page_title = item["title"]
                snippet = item.get("snippet", "")

                # Clean up the snippet (remove HTML tags)
                clean_snippet = re.sub(r"<[^>]+>", "", snippet)
                clean_snippet = re.sub(r"&[^;]+;", " ", clean_snippet)

                if clean_snippet.strip():
                    results.append(f"{page_title}: {clean_snippet.strip()}")

        return results if results else [f"No Wikipedia results found for: {query}"]

    except Exception as e:
        raise Exception(f"Wikipedia API request failed: {e}") from e


def _search_wikipedia_colbert(query: str, k: int = 3) -> list[str]:
    """
    Search Wikipedia using ColBERTv2 neural search.

    Falls back to API if ColBERT is unavailable.

    Args:
        query: Search query
        k: Number of results

    Returns:
        List of search results
    """
    try:
        from dsp.modules.colbertv2 import ColBERTv2
    except ImportError:
        logger.warning("ColBERTv2 not available, falling back to API search")
        logger.debug("Install dspy for ColBERT support")
        return _search_wikipedia_api(query, max_results=k)

    logger.info("ColBERTv2: %s", query)
    try:
        colbert = ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")
        colbert_results: Any = colbert(query, k=k, max_retries=1)
        return [str(item.text) for item in colbert_results if hasattr(item, "text")]
    except Exception as e:
        logger.info("ColBERTv2 failed (%s), fallback to Wikipedia API", e)
        return _search_wikipedia_api(query, max_results=k)


def _search_wikipedia_bm25(
    query: str,
    n: int = 5,
    index_dir: str | None = None,
    hf_repo: str | None = None,
) -> list[str]:
    """
    Search Wikipedia using BM25 retrieval.

    Falls back to API if BM25 is unavailable or no index provided.

    Args:
        query: Search query
        n: Number of results
        index_dir: Path to local BM25 index
        hf_repo: HuggingFace repo ID for BM25 index

    Returns:
        List of search results
    """
    try:
        import bm25s
    except ImportError:
        logger.warning("bm25s not available, falling back to API search")
        logger.debug("Install with: pip install bm25s[full]")
        return _search_wikipedia_api(query, max_results=n)

    if not index_dir and not hf_repo:
        logger.warning("No BM25 index provided, falling back to API search")
        return _search_wikipedia_api(query, max_results=n)

    try:
        # Load BM25 index
        if hf_repo:
            index_path = _download_bm25_index(hf_repo)
        elif index_dir:
            index_path = Path(index_dir)
        else:
            # This shouldn't happen due to the check above, but for type safety
            raise ValueError("Either index_dir or hf_repo must be provided")

        if not index_path.exists():
            raise FileNotFoundError(f"Index directory not found: {index_path}")

        # Load retriever and corpus
        # Check if we have Parquet corpus (optimized format) or JSONL (standard)
        parquet_files = list(index_path.glob("corpus_*.parquet"))
        jsonl_files = list(index_path.glob("*corpus*.jsonl"))

        if parquet_files:
            # Optimized Parquet format - load BM25 index without corpus
            logger.debug(f"Found {len(parquet_files)} Parquet corpus files")
            retriever = bm25s.BM25.load(str(index_path), load_corpus=False)

            # Load corpus from Parquet chunks
            try:
                import importlib

                # Optional dependency; load dynamically to avoid hard dependency on pyarrow
                pq = cast(Any, importlib.import_module("pyarrow.parquet"))
            except ImportError:
                logger.warning(
                    "pyarrow not available for Parquet corpus, falling back to API"
                )
                return _search_wikipedia_api(query, max_results=n)

            # Load all Parquet chunks and combine
            corpus_list = []
            for parquet_file in sorted(parquet_files):
                table = pq.read_table(parquet_file, columns=["title", "text"])
                df = table.to_pandas()
                # Combine title and text back to "Title | Text" format
                corpus_list.extend(
                    df.apply(
                        lambda row: f"{row['title']} | {row['text']}", axis=1
                    ).tolist()
                )
            corpus = corpus_list
            logger.debug(f"Loaded {len(corpus)} documents from Parquet")

        elif jsonl_files:
            # Standard JSONL format
            logger.debug("Found JSONL corpus file")
            retriever = bm25s.BM25.load(str(index_path), load_corpus=True)
            corpus = retriever.corpus
        else:
            raise FileNotFoundError(f"No corpus files found in {index_path}")

        if corpus is None or len(corpus) == 0:
            raise ValueError("Corpus is empty or failed to load")

        # Initialize stemmer for query tokenization (optional)
        try:
            import Stemmer

            stemmer = Stemmer.Stemmer("english")
        except ImportError:
            logger.debug("PyStemmer not available, tokenizing without stemming")
            stemmer = None

        # Tokenize query
        query_tokens = bm25s.tokenize(
            [query], stopwords="en", stemmer=stemmer if stemmer else None
        )

        # Get top n results
        results, scores = retriever.retrieve(query_tokens, k=n)

        # Extract passages
        passages = []

        for idx in results[0]:  # First query
            if idx < len(corpus):
                passage = corpus[idx]
                logger.debug(f"Retrieved doc {idx}, len={len(passage)}")
                passages.append(passage)
            else:
                logger.warning(
                    f"Index {idx} out of bounds (corpus size: {len(corpus)})"
                )

        # Pad if needed
        if len(passages) < n:
            logger.debug(f"Padding {n - len(passages)} empty results")
            passages.extend([""] * (n - len(passages)))

        return passages[:n]

    except Exception as e:
        logger.warning("BM25 search failed (%s), falling back to API search", e)
        return _search_wikipedia_api(query, max_results=n)


def _download_bm25_index(repo_id: str, cache_dir: str | None = None) -> Path:
    """
    Download BM25 index from HuggingFace Hub.

    Args:
        repo_id: HuggingFace repo ID (default: "Comet/wikipedia-2017-bm25")
        cache_dir: Local cache directory (default: ~/.opik_optimizer/.cache/wikipedia_bm25)

    Returns:
        Path to downloaded index
    """
    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        raise ImportError(
            "huggingface_hub not installed. Install with: pip install huggingface-hub"
        )

    # Use default cache directory in user's home
    if cache_dir is None:
        cache_dir = str(Path.home() / ".opik_optimizer" / ".cache" / "wikipedia_bm25")

    logger.info(f"Downloading BM25 index from HuggingFace: {repo_id}")

    cache_path = Path(cache_dir)
    cache_path.mkdir(parents=True, exist_ok=True)

    local_dir = cache_path / repo_id.replace("/", "_")
    snapshot_download(
        repo_id=repo_id,
        repo_type="dataset",
        local_dir=str(local_dir),
        local_dir_use_symlinks=False,
    )

    logger.info(f"Downloaded to {local_dir}")
    return local_dir
