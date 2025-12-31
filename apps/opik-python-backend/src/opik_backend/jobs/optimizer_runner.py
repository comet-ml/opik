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

# Terminal width for Rich output formatting (configurable via env var)
# Default to 120 to accommodate Rich panels (up to 90 chars) with nesting prefixes
TERMINAL_WIDTH = int(os.environ.get("OPTSTUDIO_LOG_TERM_WIDTH", "120"))

# Set terminal size for Rich output formatting BEFORE importing Rich
os.environ["COLUMNS"] = str(TERMINAL_WIDTH)
os.environ["LINES"] = "50"
# Force Rich to think it has a terminal
os.environ["FORCE_COLOR"] = "1"
os.environ["TERM"] = "xterm-256color"

# Configure logging to stderr for subprocess capture
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    stream=sys.stderr,
    force=True,
)

logger = logging.getLogger(__name__)


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
            if hasattr(opik_optimizer, 'console'):
                opik_optimizer.console = stderr_console
        except Exception:
            pass
            
        logger.info("Configured Rich console to output to stderr")
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
        
        logger.info(f"Optimizer runner started with job: {job_message.get('optimization_id', 'unknown')}")
        
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
        logger.info(f"Using model: {config.model} with params: {config.model_params}")
        
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

