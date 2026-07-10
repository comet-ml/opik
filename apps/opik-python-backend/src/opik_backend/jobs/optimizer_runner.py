#!/usr/bin/env python
"""
Optimizer runner script for Optimization Studio.

This script runs in an isolated subprocess and performs the actual optimization.
It reads job data from stdin, runs the optimization, and outputs results to stdout.

All stdout/stderr from this script is captured by the parent process and
streamed to Redis for S3 sync.
"""

import functools
import json
import logging
import os
import sys
import traceback
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

# Configure opik_optimizer log level separately (default: DEBUG to show optimizer output)
# This can be set via OPIK_OPTIMIZER_LOG_LEVEL env var (automatically inherited by subprocess)
OPTIMIZER_LOG_LEVEL = os.environ.get("OPIK_OPTIMIZER_LOG_LEVEL", "DEBUG").upper()
logging.getLogger("opik_optimizer").setLevel(getattr(logging, OPTIMIZER_LOG_LEVEL, logging.DEBUG))

# Suppress Pydantic serialization warnings from LiteLLM
# These occur due to LiteLLM's varying response structures across providers
warnings.filterwarnings("ignore", category=UserWarning, module="pydantic")

logger = logging.getLogger(__name__)
logger.debug(f"TERMINAL_WIDTH: {TERMINAL_WIDTH}")

# Opik backend gateway base URL for LLM calls. optimizer.py sets this in the
# subprocess environment before spawning this runner whenever Optimization
# Studio routes completions through the backend gateway, so it is read once at
# import (mirrors OPIK_URL handling in studio/config.py).
OPENAI_API_BASE = os.getenv("OPENAI_API_BASE")
logger.debug(f"OPENAI_API_BASE configured: {bool(OPENAI_API_BASE)}")


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


# Workspace the gateway wrappers inject into the Comet-Workspace header. Held in
# a mutable container so the (single) set of wrappers always reflects the latest
# workspace without needing to be torn down and rebuilt.
_gateway_workspace = {"name": None}


def route_litellm_calls_through_gateway(workspace_name):
    """Attach the ``Comet-Workspace`` header to gateway LLM calls.

    Optimization Studio routes every LiteLLM completion through the Opik
    backend gateway (``OPENAI_API_BASE``, set in optimizer.py). The gateway
    authenticates each request per workspace, reading the ``Comet-Workspace``
    header — the same header the SDK and playground send. LiteLLM's
    OpenAI-compatible path only forwards ``Authorization`` by default, so
    without this header the gateway rejects every call with
    403 "Workspace name should be provided", regardless of project name.

    The header is injected via ``extra_headers`` (which LiteLLM merges with the
    auth header) by wrapping ``litellm.completion``/``litellm.acompletion``.
    Both call sites in opik_optimizer resolve these attributes at call time, so
    patching them before the optimization runs is sufficient. Guarded on
    ``OPENAI_API_BASE`` so direct-provider calls are never touched.

    Idempotent: the wrappers are installed at most once. Calling again with a
    different ``workspace_name`` updates the injected value in place rather than
    stacking another wrapper.
    """
    if not workspace_name or not OPENAI_API_BASE:
        return

    import litellm

    # Always reflect the latest workspace; the wrappers read this dynamically.
    _gateway_workspace["name"] = workspace_name

    # Install the wrappers only once — they pick up workspace changes via the
    # shared container above.
    if getattr(litellm.completion, "_opik_gateway_wrapped", False):
        return

    def _add_workspace_header(kwargs):
        extra_headers = dict(kwargs.get("extra_headers") or {})
        extra_headers.setdefault("Comet-Workspace", _gateway_workspace["name"])
        kwargs["extra_headers"] = extra_headers
        return kwargs

    original_completion = litellm.completion
    original_acompletion = litellm.acompletion

    @functools.wraps(original_completion)
    def completion_with_workspace(*args, **kwargs):
        return original_completion(*args, **_add_workspace_header(kwargs))

    @functools.wraps(original_acompletion)
    async def acompletion_with_workspace(*args, **kwargs):
        return await original_acompletion(*args, **_add_workspace_header(kwargs))

    completion_with_workspace._opik_gateway_wrapped = True
    acompletion_with_workspace._opik_gateway_wrapped = True

    litellm.completion = completion_with_workspace
    litellm.acompletion = acompletion_with_workspace
    logger.debug(
        "Routing LiteLLM calls through gateway with Comet-Workspace header"
    )


def _gateway_model(model: str) -> str:
    """Prefix with ``openai/`` so LiteLLM uses its OpenAI handler — the only one
    that honors ``OPENAI_API_BASE`` (the gateway). LiteLLM strips the prefix, so
    the gateway still receives the provider-qualified model and routes it (e.g.
    ``vertex_ai/gemini-2.5-flash``)."""
    return model if model.startswith("openai/") else f"openai/{model}"


def _with_stream(params):
    """The gateway requires an explicit ``stream`` field; LiteLLM omits it by
    default, which NPEs the Java backend's Anthropic mapper."""
    params = dict(params or {})
    params.setdefault("stream", False)
    return params


def build_optimizer_and_prompt(config):
    """Build the optimizer and the prompt for a parsed ``OptimizationConfig``.

    The prompt (task evaluation) uses the configured prompt model and its
    parameters; the optimizer/algorithm uses its own configured model and
    parameters (GEPA's reflection LM, hierarchical's reasoning model), defaulting
    to the prompt model when none was picked. Both models are routed through the
    gateway. ``config`` is not modified — the gateway-routed task model is
    carried on the returned ``prompt.model``, which the metric reuses.

    Returns ``(optimizer, prompt)``.
    """
    from opik_optimizer import ChatPrompt
    from opik_backend.studio.optimizers import (
        OptimizerFactory,
        ensure_default_model_params,
    )

    task_model = _gateway_model(config.model)
    task_params = _with_stream(config.model_params)

    if config.optimizer_model:
        optimizer_model = _gateway_model(config.optimizer_model)
        optimizer_model_params = _with_stream(config.optimizer_model_params)
    else:
        # No separate algorithm model — default to the prompt model. Still honor
        # optimizer model_parameters if the config set them without a model
        # (saved configs / API clients), instead of silently dropping them.
        optimizer_model = task_model
        optimizer_model_params = (
            _with_stream(config.optimizer_model_params)
            if config.optimizer_model_params is not None
            else task_params
        )

    # The factory injects defaults (e.g. max_tokens) into the optimizer params.
    optimizer = OptimizerFactory.build(
        config.optimizer_type,
        optimizer_model,
        optimizer_model_params,
        config.optimizer_params or {},
    )
    # Set the model on the prompt itself — the optimizer uses its own model for
    # reasoning, while baseline/per-trial task calls run through the prompt's
    # model. Apply the same max_tokens default so task calls don't truncate.
    prompt = ChatPrompt(
        messages=config.prompt_messages,
        model=task_model,
        model_parameters=ensure_default_model_params(task_params),
    )
    return optimizer, prompt


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
        from opik_backend.studio import OptimizationJobContext
        from opik_backend.studio.types import (
            OptimizationConfig,
            OptimizationRunResult,
        )
        from opik_backend.studio.helpers import (
            initialize_opik_client,
            load_and_validate_dataset,
            run_optimization,
        )
        from opik_backend.studio.metrics import MetricFactory
        from opik_backend.studio.status_manager import (
            OptimizationStatusManager,
            optimization_lifecycle,
        )

        # Parse job context and config
        context = OptimizationJobContext.from_job_message(job_message)
        config = OptimizationConfig.from_dict(job_message.get("config", {}))

        # Ensure every gateway-routed LLM call carries the workspace header so
        # the backend can authenticate it (see optimizer.py for OPENAI_API_BASE).
        route_litellm_calls_through_gateway(context.workspace_name)

        # Ensure optimizer_params is a dict and force verbose for Rich output.
        config.optimizer_params = config.optimizer_params or {}
        config.optimizer_params["verbose"] = True

        logger.debug(f"Processing optimization: {context.optimization_id}")

        # Initialize Opik client (sets env vars for SDK)
        client = initialize_opik_client(context)

        # Create status manager
        status_manager = OptimizationStatusManager(client, str(context.optimization_id))

        # Run optimization with lifecycle management
        with optimization_lifecycle(status_manager):
            # Load dataset
            dataset = load_and_validate_dataset(client, config.dataset_name)

            # Build the optimizer + prompt: resolves the gateway-routed prompt
            # (task) model and the optimizer (algorithm) model, each with their
            # configured parameters. See build_optimizer_and_prompt.
            optimizer, prompt = build_optimizer_and_prompt(config)

            # Build metric function — reuse the prompt's gateway-routed model so
            # LLM-as-judge metrics route through the gateway too.
            metric_fn = MetricFactory.build(
                config.metric_type,
                config.metric_params,
                prompt.model,
                dataset_items_provider=lambda: list(dataset.get_items()),
            )

            # Run optimization
            result = run_optimization(
                optimizer=optimizer,
                optimization_id=str(context.optimization_id),
                prompt=prompt,
                dataset=dataset,
                metric_fn=metric_fn,
                project_name=context.project_name,
            )

            # Build result dict
            output: OptimizationRunResult = {
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

        # Output error as JSON, including the full traceback so the parent
        # process (and CI) can surface what failed inside this subprocess.
        # Local import: an exception can fire before the deferred import above.
        from opik_backend.studio.types import OptimizationErrorResult

        # Classify HERE, where the real exception object (typed Studio errors and
        # provider SDK exceptions) is available, into a high-level user-facing
        # message. Fall back to a generic message if even the classifier import
        # fails (e.g. a very early environment failure).
        try:
            from opik_backend.studio.errors import to_user_facing_message

            user_message = to_user_facing_message(e)
        except Exception:
            user_message = (
                "The optimization run ran into an unexpected error and stopped. "
                "Open the logs for the full details."
            )

        error_output: OptimizationErrorResult = {
            "success": False,
            "error": str(e),
            "user_message": user_message,
            "traceback": traceback.format_exc(),
        }
        print(json.dumps(error_output))
        sys.exit(1)


if __name__ == "__main__":
    main()
