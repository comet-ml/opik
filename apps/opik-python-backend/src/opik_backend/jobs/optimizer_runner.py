#!/usr/bin/env python
"""
Optimizer runner script for Optimization Studio.

This script runs in an isolated subprocess and performs the actual optimization.
It reads job data from stdin, runs the optimization, and outputs results to stdout.

All stdout/stderr from this script is captured by the parent process and
streamed to Redis for S3 sync.
"""

import json
import logging
import os
import sys
import warnings

# =============================================================================
# IMPORTANT: Deferred imports pattern
# =============================================================================
# Most imports in this file are intentionally placed AFTER environment setup,
# not at the top of the file. This is because:
#
# 1. Rich library reads COLUMNS/LINES env vars at import time to determine
#    terminal dimensions. We must set these BEFORE importing Rich.
#
# 2. opik_optimizer imports Rich internally, so we must configure the
#    environment before importing opik_optimizer.
#
# 3. The Rich console must be reconfigured to write to stderr (for log capture)
#    before opik_optimizer creates its internal console instances.
#
# Import order:
#   1. Standard library (json, logging, os, sys) - safe at top
#   2. Set environment variables (COLUMNS, LINES, FORCE_COLOR, TERM)
#   3. reconfigure_rich_console() - patches Rich's default console
#   4. opik_optimizer and opik_backend modules - now safe to import
# =============================================================================

# Terminal width for Rich output formatting (configurable via env var)
# Default to 150 to accommodate Rich panels with nesting prefixes and emojis
TERMINAL_WIDTH = int(os.environ.get("OPTSTUDIO_LOG_TERM_WIDTH", "150"))

# Set terminal size for Rich output formatting BEFORE importing Rich
os.environ["COLUMNS"] = str(TERMINAL_WIDTH)
os.environ["LINES"] = "50"
# Force Rich to think it has a terminal
os.environ["FORCE_COLOR"] = "1"
os.environ["TERM"] = "xterm-256color"

# Configure logging to stderr for subprocess capture
# Log level is configurable via env var for debugging (default: INFO)
LOG_LEVEL = os.environ.get("OPTSTUDIO_LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    stream=sys.stderr,
    force=True,
)

# Suppress noisy third-party library logs
logging.getLogger("pyrate_limiter").setLevel(logging.WARNING)
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)
logging.getLogger("LiteLLM").setLevel(logging.WARNING)

# Suppress Pydantic serialization warnings from LiteLLM
# These occur due to LiteLLM's varying response structures across providers
warnings.filterwarnings("ignore", category=UserWarning, module="pydantic")

logger = logging.getLogger(__name__)
logger.debug(f"TERMINAL_WIDTH: {TERMINAL_WIDTH}")


def reconfigure_rich_console():
    """Reconfigure Rich console to output to stderr so we can capture it.

    Rich by default writes to stdout, but we need it on stderr so the
    subprocess capture can collect it while keeping stdout clean for JSON result.
    """
    try:
        from rich.console import Console
        import rich

        # Create a new console that writes to stderr
        stderr_console = Console(
            file=sys.stderr,
            force_terminal=True,
            width=TERMINAL_WIDTH,
            color_system="256",
        )

        # Replace the default console used by Rich
        rich.reconfigure(stderr=stderr_console)

        # Also try to patch the opik_optimizer's console if it has one
        try:
            import opik_optimizer

            if hasattr(opik_optimizer, "console"):
                opik_optimizer.console = stderr_console
        except Exception:
            pass

        logger.debug("Configured Rich console to output to stderr")
    except ImportError:
        logger.warning("Rich not available, skipping console reconfiguration")
    except Exception as e:
        logger.warning(f"Failed to reconfigure Rich console: {e}")


def main():
    """Main entry point for optimizer runner subprocess."""
    try:
        # Read input from stdin
        input_data = sys.stdin.read()
        if not input_data:
            raise ValueError("No input data received from stdin")

        payload = json.loads(input_data)
        job_message = payload.get("data", {})

        logger.debug(
            f"Optimizer runner started with job: {job_message.get('optimization_id', 'unknown')}"
        )

        # Reconfigure Rich console BEFORE importing opik_optimizer
        reconfigure_rich_console()

        # Import after setting up environment
        from opik_optimizer import ChatPrompt

        from opik_backend.studio import OptimizationJobContext
        from opik_backend.studio.types import OptimizationConfig
        from opik_backend.studio.helpers import (
            initialize_opik_client,
            load_and_validate_dataset,
            run_optimization,
        )
        from opik_backend.studio.metrics import MetricFactory
        from opik_backend.studio.optimizers import OptimizerFactory
        from opik_backend.studio.status_manager import (
            OptimizationStatusManager,
            optimization_lifecycle,
        )

        # Parse job context and config
        context = OptimizationJobContext.from_job_message(job_message)
        config = OptimizationConfig.from_dict(job_message.get("config", {}))

        # Ensure optimizer_params is a dict before mutating
        config.optimizer_params = config.optimizer_params or {}

        # Force verbose mode for testing Rich output
        config.optimizer_params["verbose"] = True

        logger.info(f"Processing optimization: {context.optimization_id}")
        logger.debug(f"Using model: {config.model} with params: {config.model_params}")

        # Initialize Opik client (sets env vars for SDK)
        client = initialize_opik_client(context)

        # Create status manager
        status_manager = OptimizationStatusManager(client, str(context.optimization_id))

        # Run optimization with lifecycle management
        with optimization_lifecycle(status_manager):
            # Load dataset
            dataset = load_and_validate_dataset(client, config.dataset_name)

            # Build metric function
            metric_fn = MetricFactory.build(
                config.metric_type,
                config.metric_params,
                config.model,
            )

            # Build optimizer
            optimizer = OptimizerFactory.build(
                config.optimizer_type,
                config.model,
                config.model_params,
                config.optimizer_params,
            )

            # Create prompt from config
            prompt = ChatPrompt(messages=config.prompt_messages)

            # Run optimization
            result = run_optimization(
                optimizer=optimizer,
                optimization_id=str(context.optimization_id),
                prompt=prompt,
                dataset=dataset,
                metric_fn=metric_fn,
            )

            # Build result dict
            output = {
                "success": True,
                "optimization_id": str(context.optimization_id),
                "score": result.score,
                "initial_score": result.initial_score,
            }

            # Add optimized prompt if available
            if hasattr(result, "prompt") and result.prompt:
                # result.prompt could be a ChatPrompt object or a list of messages
                if hasattr(result.prompt, "messages"):
                    output["optimized_prompt"] = result.prompt.messages
                elif isinstance(result.prompt, list):
                    output["optimized_prompt"] = result.prompt
                else:
                    output["optimized_prompt"] = str(result.prompt)

        # Output result as JSON on last line of stdout
        print(json.dumps(output))

    except Exception as e:
        logger.exception(f"Optimization failed: {e}")

        # Output error as JSON
        error_output = {
            "success": False,
            "error": str(e),
        }
        print(json.dumps(error_output))
        sys.exit(1)


if __name__ == "__main__":
    main()
