from typing import Dict, Any, List, Optional
import dspy
from dspy.teleprompt.mipro_optimizer_v2 import MIPROv2
from dspy.teleprompt import BootstrapFewShot
from dspy.evaluate import Evaluate
from dspy.primitives import Example
from adalflow.optim import (
    AdalComponent,
    Trainer,
    TGDOptimizer,
    BootstrapFewShot
)
from adalflow.optim.parameter import Parameter
from adalflow.optim.types import ParameterType
import adalflow as adal
from opik_optimizer.integrations.dspy.utils import opik_metric_to_dspy
from opik.integrations.dspy.callback import OpikCallback

class ExternalDspyMiproOptimizer:
    """Wrapper for DSPy MIPRO optimizer to use our evaluation metrics."""
    
    def __init__(self, model: str, temperature: float = 0.1, max_tokens: int = 5000, num_threads: int = 1, seed: int = 9, project_name: str = None):
        self.model = model
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.num_threads = num_threads
        self.num_candidates = 10
        self.seed = seed
        self.project_name = project_name
        
        # Configure DSPy settings
        self.lm = dspy.LM(
            model=model,
            temperature=temperature,
            max_tokens=max_tokens
        )
        
        # Set up Opik callback for DSPy
        self.opik_callback = OpikCallback(project_name=self.project_name)
        
        # Configure DSPy with both LM and callback
        dspy.settings.configure(
            lm=self.lm,
            callbacks=[self.opik_callback]
        )
        
        # Initialize DSPy MIPRO
        self.teleprompter = MIPROv2(
            metric=None,  # Will be set during optimization
            prompt_model=self.lm,
            task_model=self.lm,
            max_bootstrapped_demos=4,
            max_labeled_demos=4,
            num_threads=num_threads,
            verbose=False,
            num_candidates=self.num_candidates,
            seed=self.seed
        )
            
    def optimize_prompt(self, dataset: Any, metric: Any, prompt: str, input_key: str, output_key: str, experiment_config: Dict, progress_callback: callable = None):
        """Run optimization using DSPy MIPRO but evaluate with our metrics."""
        # Convert our dataset format to DSPy format
        dspy_dataset = self._convert_dataset(dataset, input_key, output_key)
        
        # Create a DSPy signature
        signature = dspy.Signature(
            f"{input_key} -> {output_key}",
            f"{prompt}\n\nGiven the input, provide the output."
        )
        
        # Create a DSPy program
        class BasicQA(dspy.Module):
            def __init__(self):
                super().__init__()
                self.prog = dspy.ChainOfThought(signature)
            
            def forward(self, **kwargs):
                return self.prog(**kwargs)
        
        # Create program instance
        program = BasicQA()
        
        # Convert the metric to a DSPy-compatible function
        dspy_metric = opik_metric_to_dspy(metric, output_key)
        
        # Update teleprompter with our wrapped metric
        self.teleprompter.metric = dspy_metric
        
        # Run DSPy MIPRO optimization
        optimized_program = self.teleprompter.compile(
            program.deepcopy(),
            trainset=dspy_dataset,
            max_bootstrapped_demos=3,
            max_labeled_demos=3,
            requires_permission_to_run=False,
            provide_traceback=True
        )
        
        # Extract the optimized prompt
        optimized_prompt = optimized_program.prog.signature.instructions
        
        return optimized_prompt

    def evaluate_prompt(self, dataset: Any, metric: Any, prompt: str, input_key: str, output_key: str, experiment_config: Dict, num_test: int = 10) -> float:
        """Evaluate a prompt using the given metric."""
        # Convert our dataset format to DSPy format
        dspy_dataset = self._convert_dataset(dataset, input_key, output_key)
        
        # Create a DSPy signature
        signature = dspy.Signature(
            f"{input_key} -> {output_key}",
            f"{prompt}\n\nGiven the input, provide the output."
        )
        
        # Create a DSPy program
        class BasicQA(dspy.Module):
            def __init__(self):
                super().__init__()
                self.prog = dspy.ChainOfThought(signature)
            
            def forward(self, **kwargs):
                return self.prog(**kwargs)
        
        # Create program instance
        program = BasicQA()
        
        # Create evaluator
        evaluator = dspy.evaluate.Evaluate(
            devset=dspy_dataset[:num_test],
            metric=metric,
            num_threads=self.num_threads
        )
        
        # Run evaluation
        score = evaluator(program)
        
        return score

    def _convert_dataset(self, dataset: Any, input_key: str, output_key: str) -> List[Dict]:
        """Convert our dataset format to DSPy format."""
        dataset_list = []
        
        # Get items from the opik Dataset
        items = dataset.get_items()
        
        # Convert items to DSPy format
        for item in items:
            # Create a DSPy example with the input and output
            example = dspy.Example(
                **{input_key: item[input_key], output_key: item[output_key]}
            )
            # Set the input key for the example
            example = example.with_inputs(input_key)
            dataset_list.append(example)
                
        return dataset_list

class ExternalAdalFlowOptimizer:
    """Wrapper for AdalFlow optimizer to use our evaluation metrics."""
    
    def __init__(self, model: str, temperature: float = 0.1, max_tokens: int = 5000, num_threads: int = 1, seed: int = 42, api_type: str = "openai", api_base: str = "https://api.openai.com/v1"):
        self.model = model
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.num_threads = num_threads
        self.seed = seed
        self.api_type = api_type
        self.api_base = api_base
        
        # Set random seed for AdalFlow
        import random
        import numpy as np
        random.seed(seed)
        np.random.seed(seed)
        
        # Initialize OpenAI model client
        try:
            from adalflow.components.model_client.openai_client import OpenAIClient
            model_client = OpenAIClient()
            model_kwargs = {
                "model": model,
                "temperature": temperature,
                "max_tokens": max_tokens,
            }
        except ImportError:
            # Fallback to generic model client
            model_client = adal.ModelClient()
            model_kwargs = {
                "model": model,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "api_type": api_type,
                "api_base": api_base,
            }
        
        # Define the task pipeline
        class TaskPipeline(adal.Component):
            def __init__(self, model_client, model_kwargs):
                super().__init__()
                
                self.system_prompt = adal.Parameter(
                    data="",  # Will be set during optimization
                    role_desc="To give task instruction to the language model in the system prompt",
                    requires_opt=True,
                    param_type=ParameterType.PROMPT
                )
                self.system_prompt.id = "system_prompt"
                
                self.few_shot_demos = adal.Parameter(
                    data=None,
                    role_desc="To provide few shot demos to the language model",
                    requires_opt=False,
                    param_type=ParameterType.DEMOS
                )
                self.few_shot_demos.id = "few_shot_demos"
                
                # Define the template
                template = r"""<START_OF_SYSTEM_PROMPT>
{{system_prompt}}
{# Few shot demos #}
{% if few_shot_demos is not none %}
Here are some examples:
{{few_shot_demos}}
{% endif %}
<END_OF_SYSTEM_PROMPT>
<START_OF_USER>
{{input_str}}
<END_OF_USER>
"""
                
                # Create the generator
                self.generator = adal.Generator(
                    model_client=model_client,
                    model_kwargs=model_kwargs,
                    template=template,
                    prompt_kwargs={
                        "system_prompt": self.system_prompt,
                        "few_shot_demos": self.few_shot_demos,
                    },
                    use_cache=True
                )
            
            def bicall(self, input_data: Dict[str, Any], id: str = None):
                """Trainer can use bicall in both train and eval mode"""
                output = self.generator(
                    prompt_kwargs={"input_str": input_data["input"]},
                    id=id
                )
                return output
        
        # Create the task pipeline
        task = TaskPipeline(model_client, model_kwargs)
        
        # Create a simple loss function
        loss_fn = adal.EvalFnToTextLoss(
            eval_fn=lambda y, y_gt: 1.0 if str(y) == str(y_gt) else 0.0,
            eval_fn_desc="exact_match: 1 if str(y) == str(y_gt) else 0"
        )
        
        # Create the AdalComponent
        self.adal_component = adal.AdalComponent(
            task=task,
            eval_fn=lambda y, y_gt: 1.0 if str(y) == str(y_gt) else 0.0,
            loss_fn=loss_fn
        )
        
        # Set up text optimizer with the same model
        try:
            from adalflow.components.model_client.openai_client import OpenAIClient
            optimizer_model = OpenAIClient()
        except ImportError:
            optimizer_model = model_client
            
        # Configure optimizers but pass model_config to model_kwargs
        self.adal_component.configure_optimizers(
            text_optimizer=TGDOptimizer(
                params=[task.system_prompt],
                model_client=optimizer_model,
            ),
            demo_optimizer=BootstrapFewShot(params=[task.few_shot_demos])
        )
        
        # Initialize trainer
        self.trainer = Trainer(
            adaltask=self.adal_component,
            strategy="constrained",
            max_steps=8,
            num_workers=num_threads,
            train_batch_size=4,
            max_error_samples=4,
            max_correct_samples=4,
            max_proposals_per_step=5
        )
    
    def optimize_prompt(self, dataset: Any, metric: Any, prompt: str, input_key: str, output_key: str, experiment_config: Dict, progress_callback: callable = None):
        """Run optimization using AdalFlow but evaluate with our metrics."""
        # Set the initial prompt
        self.adal_component.task.system_prompt.data = prompt
        
        # Convert our dataset format to AdalFlow format
        adalflow_dataset = self._convert_dataset(dataset, input_key, output_key)
        
        # Configure the task
        self.adal_component.prepare_task = lambda sample: (
            self.adal_component.task.bicall,
            {"input_data": {"input": sample[input_key]}, "id": str(sample.get("id", ""))}
        )
        
        # Configure evaluation
        self.adal_component.prepare_eval = lambda sample, y_pred: (
            self.adal_component.eval_fn,
            {"y": y_pred.data if y_pred and y_pred.data else "", "y_gt": sample[output_key]}
        )
        
        # Run optimization
        self.trainer.fit(
            train_dataset=adalflow_dataset,
            progress_callback=progress_callback,
            raw_shots=0,  # Start with zero-shot
            bootstrap_shots=1  # Allow one-shot learning
        )
        
        # Return the optimized prompt
        return self.adal_component.task.system_prompt.data
    
    def evaluate_prompt(self, dataset: Any, metric: Any, prompt: str, input_key: str, output_key: str, experiment_config: Dict, num_test: int = 10) -> float:
        """Evaluate a prompt using the given metric."""
        # Set the prompt
        self.adal_component.task.system_prompt.data = prompt
        
        # Convert our dataset format to AdalFlow format
        adalflow_dataset = self._convert_dataset(dataset, input_key, output_key)
        
        # Configure the task
        self.adal_component.prepare_task = lambda sample: (
            self.adal_component.task.bicall,
            {"input_data": {"input": sample[input_key]}, "id": str(sample.get("id", ""))}
        )
        
        # Configure evaluation
        self.adal_component.prepare_eval = lambda sample, y_pred: (
            self.adal_component.eval_fn,
            {"y": y_pred.data if y_pred and y_pred.data else "", "y_gt": sample[output_key]}
        )
        
        # Run evaluation
        score = self.trainer.evaluate(test_dataset=adalflow_dataset[:num_test])
        
        return score
    
    def _convert_dataset(self, dataset: Any, input_key: str, output_key: str) -> List[Dict]:
        """Convert our dataset format to AdalFlow format."""
        dataset_list = []
        
        # Get items from the opik Dataset
        items = dataset.get_items()
        
        # Convert items to AdalFlow format
        for item in items:
            dataset_list.append({
                "input": item[input_key],
                "output": item[output_key]
            })
                
        return dataset_list 