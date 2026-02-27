#!/usr/bin/env python
"""
Framework optimizer runner script.

This script runs in an isolated subprocess and performs optimization using
the new opik_optimizer_framework package. It follows the same pattern as
optimizer_runner.py but routes to the framework's run_optimization().
"""

import json
import logging
import sys

from opik_backend.jobs.runner_common import setup_runner_environment

# Must run before importing opik_optimizer_framework (Rich reads env at import time)
setup_runner_environment()

logger = logging.getLogger(__name__)


def main():
    """Main entry point for framework optimizer runner subprocess."""
    try:
        input_data = sys.stdin.read()
        if not input_data:
            raise ValueError("No input data received from stdin")

        payload = json.loads(input_data)
        job_message = payload.get("data", {})

        logger.debug(
            f"Framework runner started with job: {job_message.get('optimization_id', 'unknown')}"
        )

        # Import after environment setup
        from opik_backend.studio import OptimizationJobContext
        from opik_backend.studio.types import OptimizationConfig
        from opik_backend.studio.helpers import initialize_opik_client, load_and_validate_dataset
        from opik_backend.studio.status_manager import (
            OptimizationStatusManager,
            optimization_lifecycle,
        )
        from opik_optimizer_framework import run_optimization, OptimizationContext

        context = OptimizationJobContext.from_job_message(job_message)
        config = OptimizationConfig.from_dict(job_message.get("config", {}))

        logger.debug(f"Processing framework optimization: {context.optimization_id}")
        logger.debug(f"Using model: {config.model} with params: {config.model_params}")

        client = initialize_opik_client(context)
        status_manager = OptimizationStatusManager(client, str(context.optimization_id))

        with optimization_lifecycle(status_manager):
            dataset = load_and_validate_dataset(client, config.dataset_name)

            # Get dataset item IDs
            dataset_items = dataset.get_items()
            dataset_item_ids = [str(item["id"]) for item in dataset_items]

            # Build framework optimization context
            opt_context = OptimizationContext(
                optimization_id=str(context.optimization_id),
                dataset_name=config.dataset_name,
                prompt_messages=config.prompt_messages,
                model=config.model,
                model_parameters=config.model_params or {},
                metric_type=config.metric_type,
                metric_parameters=config.metric_params or {},
                optimizer_type=config.optimizer_type,
                optimizer_parameters=config.optimizer_params or {},
            )

            result = run_optimization(
                context=opt_context,
                client=client,
                dataset_item_ids=dataset_item_ids,
            )

            output = {
                "success": True,
                "optimization_id": str(context.optimization_id),
                "score": result.score,
                "initial_score": result.initial_score,
            }

            if result.best_trial and result.best_trial.prompt_messages:
                output["optimized_prompt"] = result.best_trial.prompt_messages

        print(json.dumps(output))

    except Exception as e:
        logger.exception(f"Framework optimization failed: {e}")

        error_output = {
            "success": False,
            "error": str(e),
        }
        print(json.dumps(error_output))
        sys.exit(1)


if __name__ == "__main__":
    main()
