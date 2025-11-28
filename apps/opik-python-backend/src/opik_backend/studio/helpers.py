"""Helper functions for Optimization Studio job processing."""

import logging
from datetime import datetime, timezone
from typing import Callable, Any

import opik
from opik_optimizer import ChatPrompt

from .config import OPIK_URL, OPTIMIZER_RUNTIME_PARAMS
from .types import OptimizationJobContext, OptimizationConfig, OptimizationResult
from .metrics import MetricFactory
from .optimizers import OptimizerFactory
from .status_manager import OptimizationStatusManager
from .exceptions import (
    DatasetNotFoundError,
    EmptyDatasetError,
    InvalidMetricError,
    InvalidOptimizerError,
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


def create_status_manager(client: opik.Opik, optimization_id: str) -> OptimizationStatusManager:
    """Create a status manager for an optimization.
    
    Args:
        client: Opik client
        optimization_id: Optimization ID
        
    Returns:
        Status manager instance
    """
    return OptimizationStatusManager(client, optimization_id)


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


def build_prompt(opt_config: OptimizationConfig) -> ChatPrompt:
    """Build ChatPrompt from configuration.
    
    Args:
        opt_config: Optimization configuration
        
    Returns:
        ChatPrompt instance
    """
    prompt = ChatPrompt(
        messages=opt_config.prompt_messages,
        model=opt_config.model,
        model_parameters=opt_config.model_params
    )
    logger.info(f"Created prompt with {len(opt_config.prompt_messages)} messages")
    return prompt


def build_metric_function(opt_config: OptimizationConfig) -> Callable:
    """Build metric function from configuration.
    
    Args:
        opt_config: Optimization configuration
        
    Returns:
        Metric function(dataset_item, llm_output) -> ScoreResult
        
    Raises:
        InvalidMetricError: If metric type is invalid or configuration is wrong
    """
    try:
        metric_fn = MetricFactory.build(
            opt_config.metric_type,
            opt_config.metric_params,
            opt_config.model
        )
        return metric_fn
    except ValueError as e:
        raise InvalidMetricError(opt_config.metric_type, str(e))


def build_optimizer(opt_config: OptimizationConfig):
    """Build optimizer from configuration.
    
    Args:
        opt_config: Optimization configuration
        
    Returns:
        Initialized optimizer instance
        
    Raises:
        InvalidOptimizerError: If optimizer type is invalid or configuration is wrong
    """
    try:
        optimizer = OptimizerFactory.build(
            optimizer_type=opt_config.optimizer_type,
            model=opt_config.model,
            model_params=opt_config.model_params,
            optimizer_params=opt_config.optimizer_params
        )
        
        logger.info(f"Starting optimization with {opt_config.optimizer_type} optimizer")
        return optimizer
    except ValueError as e:
        raise InvalidOptimizerError(opt_config.optimizer_type, str(e))


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


def build_success_response(
    optimization_id: str,
    result: Any
) -> OptimizationResult:
    """Build success response from optimization result.
    
    Args:
        optimization_id: Optimization ID
        result: Optimization result from optimizer
        
    Returns:
        OptimizationResult object
    """
    return OptimizationResult(
        optimization_id=str(optimization_id),
        final_score=result.score,
        initial_score=result.initial_score,
        metric_name=result.metric_name,
        timestamp=datetime.now(timezone.utc).isoformat(),
    )



