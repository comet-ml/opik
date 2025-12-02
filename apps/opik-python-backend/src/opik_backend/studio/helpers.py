"""Helper functions for Optimization Studio job processing."""

import logging
from typing import Callable, Any

import opik
from opik_optimizer import ChatPrompt

from .config import OPIK_URL, OPTIMIZER_RUNTIME_PARAMS
from .types import OptimizationJobContext
from .exceptions import (
    DatasetNotFoundError,
    EmptyDatasetError,
)

logger = logging.getLogger(__name__)

def initialize_opik_client(context: OptimizationJobContext) -> opik.Opik:
    """Initialize Opik SDK client.
    
    Args:
        context: Job context containing workspace and API key info
        
    Returns:
        Initialized Opik client
    """
    opik_kwargs = {"workspace": context.workspace_name}
    
    if OPIK_URL:
        opik_kwargs["host"] = OPIK_URL
        logger.info(f"Using Opik URL override: '{OPIK_URL}'")
    
    if context.opik_api_key:
        opik_kwargs["api_key"] = context.opik_api_key
        logger.info("Using Opik API key from job message (cloud deployment)")
    else:
        logger.info("No Opik API key provided (local deployment)")
    
    client = opik.Opik(**opik_kwargs)
    logger.info(f"Opik SDK initialized for workspace: {context.workspace_name}")
    
    return client


def load_and_validate_dataset(client: opik.Opik, dataset_name: str):
    """Load dataset and validate it has items.
    
    Args:
        client: Opik client
        dataset_name: Name of the dataset to load
        
    Returns:
        Loaded dataset object
        
    Raises:
        DatasetNotFoundError: If dataset not found or inaccessible
        EmptyDatasetError: If dataset has no items
    """
    try:
        dataset = client.get_dataset(dataset_name)
        logger.info(f"Loaded dataset: {dataset_name}")
    except Exception as e:
        logger.error(f"Failed to load dataset '{dataset_name}': {e}")
        raise DatasetNotFoundError(dataset_name, e)
    
    # Validate dataset has items
    dataset_items = list(dataset.get_items())
    if not dataset_items:
        raise EmptyDatasetError(dataset_name)
    
    logger.info(f"Dataset has {len(dataset_items)} items")
    return dataset

def run_optimization(
    optimizer,
    optimization_id: str,
    prompt: ChatPrompt,
    dataset,
    metric_fn: Callable
) -> Any:
    """Run the optimization process.
    
    Args:
        optimizer: Optimizer instance
        optimization_id: Optimization ID
        prompt: Chat prompt to optimize
        dataset: Dataset to evaluate against
        metric_fn: Metric function for evaluation
        
    Returns:
        Optimization result object
    """
    result = optimizer.optimize_prompt(
        optimization_id=optimization_id,
        prompt=prompt,
        dataset=dataset,
        metric=metric_fn,
        **OPTIMIZER_RUNTIME_PARAMS
    )
    
    logger.info(f"Optimization completed successfully: {optimization_id}")
    logger.info(f"Final score: {result.score}")
    
    if result.initial_score is not None:
        logger.info(f"Initial score: {result.initial_score}")
        improvement = (
            ((result.score - result.initial_score) / result.initial_score * 100)
            if result.initial_score != 0 else 0
        )
        logger.info(f"Improvement: {improvement:.2f}%")
    
    return result
