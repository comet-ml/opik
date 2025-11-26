import logging
import os
from datetime import datetime, timezone
from rq import get_current_job
from opentelemetry import trace
import opik
from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.algorithms.evolutionary_optimizer.evolutionary_optimizer import EvolutionaryOptimizer
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_reflective_optimizer import HierarchicalReflectiveOptimizer
from opik_optimizer import ChatPrompt
from opik.evaluation.metrics import Equals, GEval, Contains, LevenshteinRatio

logger = logging.getLogger(__name__)
tracer = trace.get_tracer(__name__)


def _validate_api_keys():
    """Validate that LLM API keys are available."""
    keys = {
        "OPENAI_API_KEY": os.getenv("OPENAI_API_KEY"),
        "ANTHROPIC_API_KEY": os.getenv("ANTHROPIC_API_KEY"),
        "OPENROUTER_API_KEY": os.getenv("OPENROUTER_API_KEY"),
    }
    
    available_keys = {name: bool(value) for name, value in keys.items()}
    logger.info(f"LLM API Keys availability: {available_keys}")
    
    if not any(available_keys.values()):
        logger.warning("No LLM API keys found. Optimization may fail.")


def _parse_job_message(args, kwargs):
    """Parse job message from args or kwargs."""
    if args and isinstance(args[0], dict):
        return args[0]
    if kwargs:
        return kwargs
    raise ValueError("No job message found in args or kwargs")


def process_optimizer_job(*args, **kwargs):
    """
    Process an optimizer job from the Java backend.
    
    Expected job message structure:
    {
        "optimization_id": "uuid",
        "workspace_name": "workspace-name", 
        "config": {
            "dataset_name": "dataset-name",
            "prompt": {
                "messages": [{"role": "...", "content": "..."}]
            },
            "llm_model": {
                "provider": "openai",
                "name": "gpt-4o",
                "parameters": {"temperature": 0.7}
            },
            "evaluation": {
                "metrics": [
                    {"type": "equals", "parameters": {...}},
                    {"type": "geval", "parameters": {...}}
                ]
            },
            "optimizer": {
                "type": "gepa",
                "parameters": {"n_iterations": 3, "n_variants_per_iteration": 2}
            }
        },
        "opik_api_key": "optional-api-key-for-cloud"
    }
    """
    with tracer.start_as_current_span("process_optimizer_job") as span:
        logger.info(f"Received optimizer job - args: {args}, kwargs: {kwargs}")
        
        try:
            # Validate API keys at job start
            _validate_api_keys()
            
            # Parse job message
            job_message = _parse_job_message(args, kwargs)
            optimization_id = job_message["optimization_id"]
            workspace_name = job_message["workspace_name"]
            config = job_message["config"]
            opik_api_key = job_message.get("opik_api_key")
            
            span.set_attribute("optimization_id", str(optimization_id))
            span.set_attribute("workspace_name", workspace_name)
            
            logger.info(f"Processing Optimization Studio job: {optimization_id} for workspace: {workspace_name}")
            
            # Initialize Opik SDK
            opik_url = os.getenv("OPIK_URL_OVERRIDE")
            opik_kwargs = {"workspace": workspace_name}
            if opik_url:
                opik_kwargs["host"] = opik_url
                logger.info(f"Using Opik URL override: '{opik_url}'")
            if opik_api_key:
                opik_kwargs["api_key"] = opik_api_key
                logger.info(f"Using Opik API key from job message (cloud deployment)")
            else:
                logger.info(f"No Opik API key provided (local deployment)")
            
            client = opik.Opik(**opik_kwargs)
            logger.info(f"Opik SDK initialized for workspace: {workspace_name}")
            
            # Update status to RUNNING when worker picks up the job
            logger.info(f"Updating optimization {optimization_id} status to 'running'")
            client._rest_client.optimizations.update_optimizations_by_id(optimization_id, status="running")
            logger.info(f"Optimization {optimization_id} status updated to 'running'")
            
            # Load dataset
            dataset_name = config["dataset_name"]
            try:
                dataset = client.get_dataset(dataset_name)
                logger.info(f"Loaded dataset: {dataset_name}")
            except Exception as e:
                logger.error(f"Failed to load dataset '{dataset_name}': {e}")
                raise ValueError(f"Dataset '{dataset_name}' not found or inaccessible. Please create the dataset before running optimization.") from e
            
            # Validate dataset has items
            dataset_items = list(dataset.get_items())
            if not dataset_items:
                raise ValueError(f"Dataset '{dataset_name}' is empty. Please add items to the dataset before running optimization.")
            
            logger.info(f"Dataset has {len(dataset_items)} items")
            
            # Build LLM config first (needed for prompt)
            llm_config = config["llm_model"]
            model = f"{llm_config['provider']}/{llm_config['name']}"
            model_params = llm_config.get("parameters", {})
            logger.info(f"Using model: {model} with params: {model_params}")
            
            # Build prompt with model
            prompt_messages = config["prompt"]["messages"]
            prompt = ChatPrompt(
                messages=prompt_messages,
                model=model,
                model_parameters=model_params
            )
            logger.info(f"Created prompt with {len(prompt_messages)} messages")
            
            # Build metric function
            # Note: optimizer expects a function(dataset_item, llm_output) -> ScoreResult
            metric_config_list = config["evaluation"]["metrics"]
            if not metric_config_list:
                raise ValueError("At least one metric must be defined")
            
            # For now, use the first metric
            metric_config = metric_config_list[0]
            metric_type = metric_config["type"]
            metric_params = metric_config.get("parameters", {})
            
            if metric_type == "equals":
                # Equals metric compares output with reference from dataset
                case_sensitive = metric_params.get("case_sensitive", False)
                reference_key = metric_params.get("reference_key", "answer")
                equals_metric = Equals(case_sensitive=case_sensitive)
                
                def metric_fn(dataset_item, llm_output):
                    reference = dataset_item.get(reference_key, "")
                    return equals_metric.score(reference=reference, output=llm_output)
                
                metric_fn.__name__ = "equals"
                
            elif metric_type == "geval":
                # GEval requires task_introduction and evaluation_criteria
                task_intro = metric_params.get("task_introduction", "Evaluate the output")
                eval_criteria = metric_params.get("evaluation_criteria", "")
                geval_metric = GEval(
                    task_introduction=task_intro,
                    evaluation_criteria=eval_criteria,
                    model=model
                )
                
                def metric_fn(dataset_item, llm_output):
                    return geval_metric.score(
                        input=dataset_item,
                        output=llm_output
                    )
                
                metric_fn.__name__ = "geval"
            
            elif metric_type == "contains":
                # Contains metric checks if the reference is contained in the output
                reference_key = metric_params.get("reference_key", "answer")
                case_sensitive = metric_params.get("case_sensitive", True)
                contains_metric = Contains(case_sensitive=case_sensitive)
                
                def metric_fn(dataset_item, llm_output):
                    reference = dataset_item.get(reference_key, "")
                    return contains_metric.score(output=llm_output, reference=reference)
                
                metric_fn.__name__ = "contains"
            
            elif metric_type == "levenshtein_ratio":
                # LevenshteinRatio metric computes string similarity
                reference_key = metric_params.get("reference_key", "answer")
                levenshtein_metric = LevenshteinRatio()
                
                def metric_fn(dataset_item, llm_output):
                    reference = dataset_item.get(reference_key, "")
                    return levenshtein_metric.score(reference=reference, output=llm_output)
                
                metric_fn.__name__ = "levenshtein_ratio"
            
            else:
                raise ValueError(f"Unknown metric type: {metric_type}")
            
            logger.info(f"Created metric function for type: {metric_type} with name: {metric_fn.__name__}")
            
            # Initialize optimizer based on config
            optimizer_config = config["optimizer"]
            optimizer_type = optimizer_config["type"].lower()
            optimizer_params = optimizer_config.get("parameters", {})
            
            logger.info(f"Initializing {optimizer_type} optimizer with params: {optimizer_params}")
            
            # Create optimizer instance based on type
            if optimizer_type == "hierarchical_reflective":
                optimizer = HierarchicalReflectiveOptimizer(
                    model=model,
                    model_parameters=model_params,
                    **optimizer_params
                )
            elif optimizer_type == "evolutionary":
                optimizer = EvolutionaryOptimizer(
                    model=model,
                    model_parameters=model_params,
                    **optimizer_params
                )
            elif optimizer_type == "gepa":
                optimizer = GepaOptimizer(
                    model=model,
                    model_parameters=model_params,
                    **optimizer_params
                )
            else:
                raise ValueError(f"Unknown optimizer type: {optimizer_type}")
            
            logger.info(f"Starting optimization with {optimizer_type} optimizer")
            
            # Run optimization
            result = optimizer.optimize_prompt(
                optimization_id=optimization_id,
                prompt=prompt,
                dataset=dataset,
                metric=metric_fn,
                max_trials=7,
                n_samples=20,
                reflection_minibatch_size=5,
                candidate_selection_strategy="best"
            )
            
            logger.info(f"Optimization completed successfully: {optimization_id}")
            logger.info(f"Final score: {result.score}")
            if result.initial_score is not None:
                logger.info(f"Initial score: {result.initial_score}")
                improvement = ((result.score - result.initial_score) / result.initial_score * 100) if result.initial_score != 0 else 0
                logger.info(f"Improvement: {improvement:.2f}%")
            
            # Update status to COMPLETED on success
            logger.info(f"Updating optimization {optimization_id} status to 'completed'")
            client._rest_client.optimizations.update_optimizations_by_id(optimization_id, status="completed")
            logger.info(f"Optimization {optimization_id} status updated to 'completed'")
            
            return {
                "status": "success",
                "optimization_id": str(optimization_id),
                "final_score": result.score,
                "initial_score": result.initial_score,
                "metric_name": result.metric_name,
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
            
        except Exception as e:
            logger.error(f"Error processing optimizer job: {e}", exc_info=True)
            
            # Update status to ERROR on failure
            try:
                # Re-parse job message to get optimization_id if it failed early
                job_message = _parse_job_message(args, kwargs)
                optimization_id = job_message["optimization_id"]
                workspace_name = job_message["workspace_name"]
                
                # Re-initialize client if needed
                opik_url = os.getenv("OPIK_URL_OVERRIDE")
                opik_kwargs = {"workspace": workspace_name}
                if opik_url:
                    opik_kwargs["host"] = opik_url
                if job_message.get("opik_api_key"):
                    opik_kwargs["api_key"] = job_message["opik_api_key"]
                
                error_client = opik.Opik(**opik_kwargs)
                logger.info(f"Updating optimization {optimization_id} status to 'error'")
                error_client._rest_client.optimizations.update_optimizations_by_id(optimization_id, status="error")
                logger.info(f"Optimization {optimization_id} status updated to 'error'")
            except Exception as update_error:
                logger.error(f"Failed to update optimization status to 'error': {update_error}", exc_info=True)
            
            raise

