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
import os
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Literal, cast

import requests
from functools import lru_cache

logger = logging.getLogger(__name__)


def _sanitize_query(query: str) -> str:
    """
    Sanitize and clean Wikipedia search query.

    Removes:
    - Leading/trailing whitespace (trimmed at every step)
    - Common prefixes like "Wikipedia search query:", "Search query:", etc.
    - Wrapping quotes if they surround the entire query
    - Newlines and excessive whitespace

    Args:
        query: Raw search query string

    Returns:
        Cleaned and sanitized query string (always trimmed)
    """
    if not query:
        return ""

    # Trim whitespace at the start
    query = query.strip()
    if not query:
        return ""

    # Remove common prefixes (case-insensitive)
    prefixes = [
        "wikipedia search query:",
        "search query:",
        "wikipedia query:",
        "query:",
        "search:",
    ]
    query_lower = query.lower()
    for prefix in prefixes:
        if query_lower.startswith(prefix):
            query = query[len(prefix) :].strip()  # Trim after removing prefix
            break

    # Remove newlines and normalize whitespace (split() already handles trimming)
    query = " ".join(query.split())

    # Remove wrapping quotes if they surround the entire query
    query = query.strip()
    if len(query) >= 2:
        if (query.startswith('"') and query.endswith('"')) or (
            query.startswith("'") and query.endswith("'")
        ):
            query = query[1:-1].strip()  # Trim after removing quotes

    # Final trim to ensure no leading/trailing whitespace
    return query.strip()


def search_wikipedia(
    query: str,
    search_type: Literal["api", "colbert", "bm25"] = "api",
    k: int = 3,
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
        k: Number of results to return (default: 3).
        bm25_index_dir: Path to local BM25 index (only for search_type="bm25")
        bm25_hf_repo: HuggingFace repo for BM25 index (only for search_type="bm25")
        use_api: DEPRECATED. Use search_type parameter instead.
            - use_api=True → search_type="api"
            - use_api=False → search_type="colbert"

    Returns:
        List of search result strings

    Example:
        # API search (default, 5 results)
        results = search_wikipedia("What is Python?", k=5)

        # ColBERT search (10 results)
        results = search_wikipedia("What is Python?", search_type="colbert", k=10)

        # BM25 search from HuggingFace (5 results)
        results = search_wikipedia(
            "What is Python?",
            search_type="bm25",
            k=5,
            bm25_hf_repo="Comet/wikipedia-2017-bm25"
        )

        # BM25 search from local index (3 results, default)
        results = search_wikipedia(
            "What is Python?",
            search_type="bm25",
            bm25_index_dir="./wiki17_abstracts"
        )
    """
    # Check environment variable at call time (not at module load time)
    disable_flag = os.getenv("OPIK_DISABLE_WIKIPEDIA", "").strip().lower()
    if disable_flag in ("1", "true", "yes", "on"):
        logger.warning("Wikipedia tool disabled via OPIK_DISABLE_WIKIPEDIA.")
        return []

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
        return _search_wikipedia_api(query, k=k)
    elif search_type == "colbert":
        return _search_wikipedia_colbert(query, k=k)
    elif search_type == "bm25":
        return _search_wikipedia_bm25(
            query,
            k=k,
            index_dir=bm25_index_dir,
            hf_repo=bm25_hf_repo,
        )
    else:
        raise ValueError(
            f"Invalid search_type: {search_type}. Must be 'api', 'colbert', or 'bm25'"
        )


def _search_wikipedia_api(query: str, k: int = 3) -> list[str]:
    """
    Search Wikipedia using the Wikipedia API.

    Args:
        query: Search query (will be sanitized)
        k: Number of results to return (industry standard parameter name)

    Returns:
        List of formatted results
    """
    query = _sanitize_query(query)
    if not query:
        return ["No valid search query provided"]

    try:
        # Search for pages using the search API
        search_params: dict[str, str | int] = {
            "action": "query",
            "format": "json",
            "list": "search",
            "srsearch": query,
            "srlimit": k,
            "srprop": "snippet",
        }

        headers = {"User-Agent": "OpikOptimizer/1.0 (https://github.com/comet-ml/opik)"}
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
            for item in search_data["query"]["search"][:k]:
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
        query: Search query (will be sanitized)
        k: Number of results

    Returns:
        List of search results
    """
    query = _sanitize_query(query)
    if not query:
        return ["No valid search query provided"]

    try:
        from dsp.modules.colbertv2 import ColBERTv2
    except ImportError:
        logger.warning("ColBERTv2 not available, falling back to API search")
        logger.debug("Install dspy for ColBERT support")
        return _search_wikipedia_api(query, k=k)

    logger.info("ColBERTv2: %s", query)
    try:
        colbert = ColBERTv2(url="http://20.102.90.50:2017/wiki17_abstracts")
        colbert_results: Any = colbert(query, k=k, max_retries=1)
        return [str(item.text) for item in colbert_results if hasattr(item, "text")]
    except Exception as e:
        logger.info("ColBERTv2 failed (%s), fallback to Wikipedia API", e)
        return _search_wikipedia_api(query, k=k)


def _search_wikipedia_bm25(
    query: str,
    k: int = 3,
    index_dir: str | None = None,
    hf_repo: str | None = None,
) -> list[str]:
    """
    Search Wikipedia using BM25 retrieval.

    Falls back to API if BM25 is unavailable or no index provided.

    Args:
        query: Search query (will be sanitized)
        k: Number of results to return (industry standard parameter name)
        index_dir: Path to local BM25 index
        hf_repo: HuggingFace repo ID for BM25 index

    Returns:
        List of search results
    """
    query = _sanitize_query(query)
    if not query:
        return ["No valid search query provided"]

    try:
        import bm25s
    except ImportError:
        logger.warning("bm25s not available, falling back to API search")
        logger.debug("Install with: pip install bm25s[full]")
        return _search_wikipedia_api(query, k=k)

    if not index_dir and not hf_repo:
        logger.warning("No BM25 index provided, falling back to API search")
        return _search_wikipedia_api(query, k=k)

    try:
        index_path = _resolve_bm25_index_path(
            index_dir=Path(index_dir) if index_dir else None, repo_id=hf_repo
        )

        retriever, corpus = _load_bm25_retriever_and_corpus(str(index_path))

        # Initialize stemmer for query tokenization (optional)
        try:
            import Stemmer

            stemmer = Stemmer.Stemmer("english")
        except ImportError:
            logger.debug("PyStemmer not available, tokenizing without stemming")
            stemmer = None

        # Tokenize query
        query_tokens = bm25s.tokenize(
            [query],
            stopwords="en",
            stemmer=stemmer if stemmer else None,
            show_progress=False,
        )

        # Get top k results
        results, scores = retriever.retrieve(query_tokens, k=k, show_progress=False)

        # Extract passages
        passages = []

        for idx in results[0]:  # First query
            if idx < len(corpus):
                passage = corpus[idx]
                passages.append(passage)
            else:
                logger.warning(
                    f"Index {idx} out of bounds (corpus size: {len(corpus)})"
                )

        # Pad if needed
        if len(passages) < k:
            passages.extend([""] * (k - len(passages)))

        return passages[:k]

    except Exception as e:
        logger.warning("BM25 search failed (%s), falling back to API search", e)
        return _search_wikipedia_api(query, k=k)


@lru_cache(maxsize=1)
def _resolve_bm25_index_path(
    index_dir: Path | None,
    repo_id: str | None,
) -> Path:
    """
    Resolve the BM25 index path, downloading once if necessary.

    - If index_dir is provided: use it, download there if missing and repo_id is provided.
    - If no index_dir: rely on huggingface_hub default cache for repo_id.
    """
    if index_dir:
        if index_dir.exists():
            return index_dir
        if not repo_id:
            raise FileNotFoundError(f"BM25 index missing at {index_dir}")
        index_dir.mkdir(parents=True, exist_ok=True)
        return _cached_bm25_index(repo_id=repo_id, target_dir=str(index_dir))

    if not repo_id:
        raise FileNotFoundError(
            "BM25 index path not provided and no repo_id to download"
        )
    return _cached_bm25_index(repo_id=repo_id, target_dir=None)


def _download_bm25_index(
    repo_id: str,
    target_dir: Path | None = None,
) -> Path:
    """
    Download BM25 index from HuggingFace Hub.

    Args:
        repo_id: HuggingFace repo ID (default: "Comet/wikipedia-2017-bm25")
        target_dir: Optional local directory path to place the snapshot

    Returns:
        Path to downloaded index
    """
    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        raise ImportError(
            "huggingface_hub not installed. Install with: pip install huggingface-hub"
        )

    logger.info(f"⚙️ Wikipedia BM25: Downloading index: '{repo_id}' from huggingface")
    if target_dir is not None:
        target_dir.mkdir(parents=True, exist_ok=True)
        # If already materialized, skip re-download to avoid repeated logs/IO.
        if any(target_dir.glob("corpus_*.parquet")):
            return target_dir
        download_path = snapshot_download(
            repo_id=repo_id,
            repo_type="dataset",
            local_dir=str(target_dir),
            local_dir_use_symlinks=False,
        )
        logger.debug(f"Downloaded to {download_path}")
        return Path(download_path)
    download_path = snapshot_download(
        repo_id=repo_id,
        repo_type="dataset",
        local_dir_use_symlinks=False,
    )
    logger.debug(f"Downloaded to {download_path}")
    return Path(download_path)


@lru_cache(maxsize=1)
def _cached_bm25_index(repo_id: str, target_dir: str | None) -> Path:
    """Cache downloaded BM25 index paths per repo/target."""
    return _download_bm25_index(
        repo_id=repo_id,
        target_dir=Path(target_dir) if target_dir else None,
    )


@lru_cache(maxsize=1)
def _load_bm25_retriever_and_corpus(
    index_path_str: str, use_mmap: bool = True
) -> tuple[Any, list[str]]:
    """
    Load BM25 retriever and corpus once per process to avoid repeated heavy loads.

    Uses memory-mapping (mmap) by default to reduce memory usage. According to bm25s docs
    (https://github.com/xhluca/bm25s):
    - mmap=True: Memory-maps the index matrices (~1GB) instead of loading into RAM
    - Reduces memory from ~4-6GB to ~0.5-2GB with minimal performance impact
    - OS handles paging, query performance remains fast (<100ms per query)
    - Load time: ~0.5-1s vs ~8-25s for in-memory loading

    Example from bm25s benchmarks (NQ dataset, 2M+ documents):
    - In-memory: 8.61s load, 4.36GB RAM
    - Memory-mapped: 0.53s load, 0.49GB RAM (16x faster load, 9x less memory)

    Args:
        index_path_str: Path to the BM25 index directory
        use_mmap: Whether to use memory-mapping for the BM25 index (default: True)

    Returns:
        Tuple of (retriever, corpus)
    """
    import bm25s

    index_path = Path(index_path_str)

    # If the path string points to a JSON/JSONL file, load that; otherwise assume Parquet directory
    if index_path.suffix in {".jsonl", ".json"}:
        logger.debug(f"Loading JSON corpus from {index_path}")
        retriever = bm25s.BM25.load(
            str(index_path.parent), load_corpus=True, mmap=use_mmap
        )
        corpus = retriever.corpus or []
        logger.info("⚙️ Wikipedia BM25 Index loaded")
    else:
        parquet_files = sorted(
            [p for p in index_path.iterdir() if p.suffix == ".parquet"]
        )
        if parquet_files:
            # Load BM25 index with memory-mapping
            retriever = bm25s.BM25.load(
                str(index_path), load_corpus=False, mmap=use_mmap
            )
            logger.info("⚙️ Wikipedia BM25 Index loaded (memory-mapped)")

            try:
                import importlib

                pq = cast(Any, importlib.import_module("pyarrow.parquet"))
            except ImportError:
                raise ImportError("pyarrow not available for Parquet corpus")

            # Load corpus from Parquet files in parallel
            def _load_parquet_file(parquet_file: Path) -> tuple[int, list[str]]:
                """Load a single parquet file and return (file_index, documents)."""
                table = pq.read_table(parquet_file, columns=["title", "text"])
                df = table.to_pandas()
                # Use vectorized operations instead of df.apply
                title_col = df["title"].fillna("").astype(str)
                text_col = df["text"].fillna("").astype(str)
                return parquet_files.index(parquet_file), (
                    title_col + " | " + text_col
                ).tolist()

            # Load files in parallel (4 workers for I/O-bound operations)
            max_workers = min(4, len(parquet_files))
            corpus_parts: dict[int, list[str]] = {}

            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                futures = {
                    executor.submit(_load_parquet_file, parquet_file): parquet_file
                    for parquet_file in parquet_files
                }

                for future in as_completed(futures):
                    parquet_file = futures[future]
                    try:
                        file_idx, file_data = future.result()
                        corpus_parts[file_idx] = file_data
                        logger.debug(
                            f"Loaded {parquet_file.name}: {len(file_data):,} docs"
                        )
                    except Exception as e:
                        logger.error(f"Failed to load {parquet_file.name}: {e}")
                        raise

            # Combine in order
            corpus = []
            for i in range(len(parquet_files)):
                corpus.extend(corpus_parts[i])

            logger.info(
                f"⚙️ Wikipedia BM25 Corpus loaded: {len(corpus):,} documents from {len(parquet_files)} files"
            )
        else:
            logger.debug("Loading JSONL corpus (no Parquet files found)")
            retriever = bm25s.BM25.load(
                str(index_path), load_corpus=True, mmap=use_mmap
            )
            corpus = retriever.corpus or []
            logger.info("⚙️ Wikipedia BM25 Index loaded")

    if not corpus:
        raise ValueError("Corpus is empty or failed to load")

    return retriever, corpus
