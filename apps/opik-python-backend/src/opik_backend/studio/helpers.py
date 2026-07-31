"""Helper functions for Optimization Studio job processing."""

import logging
import os
from typing import Any, Callable, Optional

import opik
from opik_optimizer import ChatPrompt

from .config import (
    DATASET_SAMPLES,
    OPIK_URL,
    OPTIMIZER_RUNTIME_PARAMS,
    resolve_reflection_minibatch_size,
)
from .types import OptimizationJobContext
from .exceptions import (
    DatasetNotFoundError,
    EmptyDatasetError,
)

logger = logging.getLogger(__name__)


def initialize_opik_client(context: OptimizationJobContext) -> opik.Opik:
    """Initialize Opik SDK client by setting environment variables.

    Sets Opik environment variables from the job context, then creates a client.
    This ensures both our explicit client and the opik_optimizer SDK's internal
    clients use the same configuration.

    Note: This sets process-level env vars. For proper isolation in multi-tenant
    scenarios, consider using IsolatedSubprocessExecutor.

    Args:
        context: Job context containing workspace and API key info

    Returns:
        Initialized Opik client
    """
    # Set environment variables for Opik SDK
    # The opik_optimizer SDK creates its own opik.Opik() clients internally,
    # which read configuration from these environment variables.
    if context.opik_api_key:
        os.environ["OPIK_API_KEY"] = context.opik_api_key
        logger.debug("Set OPIK_API_KEY environment variable (cloud deployment)")
    else:
        # Clear any previous API key to prevent credential leakage between jobs
        os.environ.pop("OPIK_API_KEY", None)
        logger.debug("No OPIK_API_KEY provided (local deployment)")

    if context.workspace_name:
        os.environ["OPIK_WORKSPACE"] = context.workspace_name
        logger.debug(f"Set OPIK_WORKSPACE: {context.workspace_name}")
    else:
        os.environ.pop("OPIK_WORKSPACE", None)

    if OPIK_URL:
        os.environ["OPIK_URL_OVERRIDE"] = OPIK_URL
        logger.debug(f"Set OPIK_URL_OVERRIDE: {OPIK_URL}")

    # Create client - it will read from the environment variables we just set
    client = opik.Opik()
    logger.debug("Opik SDK initialized from environment variables")

    return client


def count_optimizable_items(dataset_items: list) -> int:
    """Count the items the optimizer will actually train on.

    The SDK's sampling builds its plan from dataset item ids and drops rows
    without one (``opik_optimizer/utils/sampling.py:_extract_ids``), so a plain
    ``len()`` can exceed the resulting trainset — and a mini-batch sized from
    that number would exceed it too. Count the same rows the SDK will keep.
    """
    return sum(
        1
        for item in dataset_items
        if isinstance(item, dict) and item.get("id") is not None
    )


def load_and_validate_dataset(client: opik.Opik, dataset_name: str):
    """Load dataset and validate it has items.

    Args:
        client: Opik client
        dataset_name: Name of the dataset to load

    Returns:
        Tuple of (dataset, item_count). The count is returned so callers can
        size dataset-dependent parameters without re-fetching every item; it is
        capped at DATASET_SAMPLES (only the effective sampled size matters
        downstream) and counts only items the optimizer can use — see
        count_optimizable_items.

    Raises:
        DatasetNotFoundError: If dataset not found or inaccessible
        EmptyDatasetError: If the dataset has no items, or none the optimizer
            can train on
    """
    try:
        dataset = client.get_dataset(dataset_name)
        logger.debug(f"Loaded dataset: {dataset_name}")
        # Bounded fetch, inside the same translation: access/transport failures
        # here are just as much "dataset unusable" as a failed lookup, and the
        # docstring promises DatasetNotFoundError for them.
        dataset_items = dataset.get_items(nb_samples=DATASET_SAMPLES)
    except Exception as e:
        logger.error(f"Failed to load dataset '{dataset_name}': {e}")
        raise DatasetNotFoundError(dataset_name, e)

    if not dataset_items:
        raise EmptyDatasetError(dataset_name)

    item_count = count_optimizable_items(dataset_items)
    if item_count == 0:
        # The rows exist but the SDK's sampling will drop every one of them, so
        # the optimizer would train on nothing: an empty trainset and a
        # mini-batch sized from 0. Reject it here, where the user gets a typed,
        # actionable error, instead of an opaque failure mid-run.
        raise EmptyDatasetError(
            dataset_name,
            reason="has no items the optimizer can use (every item is missing an id)",
        )
    logger.debug(
        f"Dataset has {len(dataset_items)} items (capped at {DATASET_SAMPLES}), "
        f"{item_count} usable by the optimizer"
    )
    return dataset, item_count


def run_optimization(
    optimizer,
    optimization_id: str,
    prompt: ChatPrompt,
    dataset,
    metric_fn: Callable,
    project_name: Optional[str] = None,
    dataset_size: Optional[int] = None,
) -> Any:
    """Run the optimization process.

    Args:
        optimizer: Optimizer instance
        optimization_id: Optimization ID
        prompt: Chat prompt to optimize
        dataset: Dataset to evaluate against
        metric_fn: Metric function for evaluation
        project_name: Optional Opik project name. When set, trial experiments
            and traces produced by the optimizer are attached to this project
            instead of the optimizer SDK default ("Optimization").
        dataset_size: Item count of ``dataset`` when the caller already knows
            it (load_and_validate_dataset returns it); avoids re-fetching the
            whole dataset here. Falls back to fetching when omitted.

    Returns:
        Optimization result object
    """
    # Optimizers default to optimizing only `system` messages. When the user's
    # prompt has no system message (e.g. a single user message), that leaves the
    # optimizer with zero editable components — GEPA then divides by zero while
    # round-robin selecting one. Optimize whichever roles the prompt actually
    # contains so a run succeeds for any prompt shape.
    present_roles = sorted(
        {
            message.get("role")
            for message in prompt.get_messages()
            if message.get("role") in {"system", "user", "assistant"}
        }
    )
    optimize_prompts = present_roles or "system"

    # GEPA's reflection mini-batch scales with the effective (sampled) dataset
    # size — a fixed size starves coarse 0/1 metrics of resolution (OPIK-7511).
    # Ignored by non-GEPA optimizers, like the other GEPA-specific params.
    if dataset_size is None:
        # Bounded fetch: only the effective (sampled) size matters, so never
        # materialize more than DATASET_SAMPLES items just to count them.
        dataset_size = count_optimizable_items(
            dataset.get_items(nb_samples=DATASET_SAMPLES)
        )
    effective_dataset_size = min(dataset_size, DATASET_SAMPLES)
    reflection_minibatch_size = resolve_reflection_minibatch_size(
        dataset_size=effective_dataset_size,
        max_trials=OPTIMIZER_RUNTIME_PARAMS["max_trials"],
    )
    logger.debug(
        "Resolved reflection_minibatch_size=%d (dataset_size=%d)",
        reflection_minibatch_size,
        effective_dataset_size,
    )

    result = optimizer.optimize_prompt(
        optimization_id=optimization_id,
        prompt=prompt,
        dataset=dataset,
        metric=metric_fn,
        project_name=project_name,
        optimize_prompts=optimize_prompts,
        reflection_minibatch_size=reflection_minibatch_size,
        **OPTIMIZER_RUNTIME_PARAMS,
    )

    logger.info(f"Optimization completed successfully: {optimization_id}")
    logger.info(f"Final score: {result.score}")

    if result.initial_score is not None:
        logger.info(f"Initial score: {result.initial_score}")
        improvement = (
            ((result.score - result.initial_score) / result.initial_score * 100)
            if result.initial_score != 0
            else 0
        )
        logger.info(f"Improvement: {improvement:.2f}%")

    return result
