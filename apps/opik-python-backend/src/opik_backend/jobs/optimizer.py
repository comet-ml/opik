import logging
from opentelemetry import trace

from opik_backend.studio import (
    LLM_API_KEYS,
    OptimizationJobContext,
    OptimizationConfig,
    JobMessageParseError,
    initialize_opik_client,
    create_status_manager,
    optimization_lifecycle,
    load_and_validate_dataset,
    build_prompt,
    build_metric_function,
    build_optimizer,
    run_optimization,
    build_success_response,
)

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)

def _validate_api_keys():
    """Validate that LLM API keys are available."""   
    has_keys = any(bool(value) for value in LLM_API_KEYS.values())
    
    if has_keys:
        logger.info("At least one LLM API key is available.")
    else:
        logger.warning("No LLM API keys found. Optimization may fail.")


def _parse_job_message(args, kwargs):
    """Parse job message from args or kwargs.
    
    Args:
        args: Job arguments tuple
        kwargs: Job keyword arguments dict
        
    Returns:
        Job message dictionary
        
    Raises:
        JobMessageParseError: If job message cannot be parsed
    """
    if args and isinstance(args[0], dict):
        return args[0]
    if kwargs:
        return kwargs
    raise JobMessageParseError("No job message found in args or kwargs")


def process_optimizer_job(*args, **kwargs):
    """Process an optimizer job from the Java backend.
    
    This is the main entry point for Optimization Studio jobs. It orchestrates
    the entire optimization workflow:
    1. Parse and validate job message
    2. Initialize Opik client
    3. Load and validate dataset
    4. Build prompt, metric, and optimizer
    5. Run optimization
    6. Update status and return results
    
    Expected job message structure:
    {
        "optimization_id": "uuid",
        "workspace_name": "workspace-name", 
        "config": {
            "dataset_name": "dataset-name",
            "prompt": {"messages": [{"role": "...", "content": "..."}]},
            "llm_model": {"model": "openai/gpt-4o", "parameters": {...}},
            "evaluation": {"metrics": [{"type": "...", "parameters": {...}}]},
            "optimizer": {"type": "gepa", "parameters": {...}}
        },
        "opik_api_key": "optional-api-key-for-cloud"
    }
    
    Args:
        *args: Job arguments (first arg should be job message dict)
        **kwargs: Job keyword arguments (or job message as kwargs)
        
    Returns:
        Dictionary with optimization results
        
    Raises:
        ValueError: If job message is invalid or dataset issues
        Exception: Any error during optimization (status updated to 'error')
    """
    with tracer.start_as_current_span("process_optimizer_job") as span:
        logger.info(f"Received optimizer job - args: {args}, kwargs: {kwargs}")
        
        # Validate API keys at job start
        _validate_api_keys()
        
        # Parse and validate job message
        job_message = _parse_job_message(args, kwargs)
        context = OptimizationJobContext.from_job_message(job_message)
        opt_config = OptimizationConfig.from_dict(context.config)
        
        # Set span attributes for tracing
        span.set_attribute("optimization_id", str(context.optimization_id))
        span.set_attribute("workspace_name", context.workspace_name)
        
        logger.info(
            f"Processing Optimization Studio job: {context.optimization_id} "
            f"for workspace: {context.workspace_name}"
        )
        logger.info(f"Using model: {opt_config.model} with params: {opt_config.model_params}")
        
        # Initialize Opik SDK client and status manager
        client = initialize_opik_client(context)
        status_manager = create_status_manager(client, context.optimization_id)
        
        # Use context manager for automatic status lifecycle management
        with optimization_lifecycle(status_manager):
            # Load and validate dataset
            dataset = load_and_validate_dataset(client, opt_config.dataset_name)
            
            # Build optimization components
            prompt = build_prompt(opt_config)
            metric_fn = build_metric_function(opt_config)
            optimizer = build_optimizer(opt_config)
            
            # Run optimization
            result = run_optimization(
                optimizer=optimizer,
                optimization_id=context.optimization_id,
                prompt=prompt,
                dataset=dataset,
                metric_fn=metric_fn
            )
            
            # Build and return success response
            optimization_result = build_success_response(context.optimization_id, result)
            return optimization_result.to_dict()

