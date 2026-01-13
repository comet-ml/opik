# Opik Optimizer Algorithms: In-Depth Guide

This document provides comprehensive explanations of all optimization algorithms available in the Opik Optimizer SDK, including their underlying mechanisms, use cases, differences, and implementation details.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [EvolutionaryOptimizer](#evolutionaryoptimizer)
3. [FewShotBayesianOptimizer](#fewshotbayesianoptimizer)
4. [GepaOptimizer](#gepaoptimizer)
5. [MetaPromptOptimizer](#metapromptoptimizer)
6. [HierarchicalReflectiveOptimizer](#hierarchicalreflectiveoptimizer)
7. [ParameterOptimizer](#parameteroptimizer)
8. [Algorithm Comparison](#algorithm-comparison)
9. [Choosing the Right Optimizer](#choosing-the-right-optimizer)

---

## Architecture Overview

All optimizers in the Opik Optimizer SDK inherit from `BaseOptimizer`, which provides a standardized interface and shared functionality. The architecture follows a consistent pattern:

### Core Components

**BaseOptimizer** (`base_optimizer.py`): The abstract base class that all optimizers extend. It provides:
- Lifecycle management (optimization tracking, history management)
- LLM call and tool call counters
- Opik integration for experiment tracking
- Common validation and evaluation methods
- Standardized result construction

```50:98:sdks/opik_optimizer/src/opik_optimizer/base_optimizer.py
class BaseOptimizer(ABC):
    def __init__(
        self,
        model: str,
        verbose: int = 1,
        seed: int = 42,
        model_parameters: dict[str, Any] | None = None,
        name: str | None = None,
    ) -> None:
        """
        Base class for optimizers.

        Args:
           model: LiteLLM model name for optimizer's internal reasoning/generation calls
           verbose: Controls internal logging/progress bars (0=off, 1=on)
           seed: Random seed for reproducibility
           model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
               Common params: temperature, max_tokens, max_completion_tokens, top_p,
               presence_penalty, frequency_penalty.
               See: https://docs.litellm.ai/docs/completion/input
               Note: These params control the optimizer's reasoning model, NOT the prompt evaluation.
           name: Optional name for the optimizer instance. This will be used when creating optimizations.
        """
        self.model = model
        self.reasoning_model = model
        self.model_parameters = model_parameters or {}
        self.verbose = verbose
        self.seed = seed
        self.name = name
        self._history: list[OptimizationRound] = []
        self.experiment_config = None
        self.llm_call_counter = 0
        self.tool_call_counter = 0
        self._opik_client = None  # Lazy initialization
        self.current_optimization_id: str | None = None  # Track current optimization
        self.project_name: str = "Optimization"  # Default project name

    def _reset_counters(self) -> None:
        """Reset all call counters for a new optimization run."""
        self.llm_call_counter = 0
        self.tool_call_counter = 0

    def _increment_llm_counter(self) -> None:
        """Increment the LLM call counter."""
        self.llm_call_counter += 1

    def _increment_tool_counter(self) -> None:
        """Increment the tool call counter."""
        self.tool_call_counter += 1
```

### Standardized API

All optimizers implement the same `optimize_prompt()` method signature:

```python
def optimize_prompt(
    self,
    prompt: ChatPrompt | dict[str, ChatPrompt],
    dataset: Dataset,
    metric: MetricFunction,
    agent: OptimizableAgent | None = None,
    experiment_config: dict | None = None,
    n_samples: int | None = None,
    auto_continue: bool = False,
    project_name: str = "Optimization",
    optimization_id: str | None = None,
    validation_dataset: Dataset | None = None,
    max_trials: int = 10,
    **kwargs: Any,
) -> OptimizationResult
```

This consistency allows optimizers to be chained together, where the output of one optimizer can serve as input to another.

---

## EvolutionaryOptimizer

### Algorithm Overview

The **EvolutionaryOptimizer** uses genetic algorithms (GA) to evolve and improve prompts over multiple generations. It implements a classic evolutionary computation approach with selection, crossover, mutation, and elitism.

### How It Works

The algorithm follows a 4-stage genetic algorithm cycle:

1. **Population Initialization**: Creates an initial population of prompt variations from the seed prompt
2. **Selection**: Selects the best-performing prompts using tournament selection or NSGA-II (for multi-objective optimization)
3. **Crossover**: Combines pairs of selected prompts to create offspring, either through string-based or LLM-based crossover
4. **Mutation**: Randomly modifies prompts to introduce diversity
5. **Evaluation**: Evaluates all candidates on the dataset
6. **Replacement**: Replaces the population with the new generation, preserving elite individuals

This cycle repeats for a specified number of generations until convergence or early stopping.

### Key Features

**Multi-Objective Optimization (MOO)**: When enabled, the optimizer balances two objectives:
- Maximize the primary metric score
- Minimize prompt length

This creates a Pareto front of solutions, allowing you to choose the best trade-off between performance and prompt conciseness.

```165:174:sdks/opik_optimizer/src/opik_optimizer/algorithms/evolutionary_optimizer/evolutionary_optimizer.py
        if self.enable_moo:
            if not hasattr(creator, "FitnessMulti"):
                creator.create(
                    "FitnessMulti", base.Fitness, weights=self.DEFAULT_MOO_WEIGHTS
                )
            fitness_attr = creator.FitnessMulti
        else:
            if not hasattr(creator, "FitnessMax"):
                creator.create("FitnessMax", base.Fitness, weights=(1.0,))
            fitness_attr = creator.FitnessMax
```

**Adaptive Mutation**: The mutation rate automatically adjusts based on:
- Population diversity (low diversity → higher mutation)
- Recent improvement rate (stagnation → higher mutation)

```247:284:sdks/opik_optimizer/src/opik_optimizer/algorithms/evolutionary_optimizer/evolutionary_optimizer.py
    def _get_adaptive_mutation_rate(self) -> float:
        """Calculate adaptive mutation rate based on population diversity and progress."""
        if not self.adaptive_mutation or len(self._best_fitness_history) < 2:
            return self.mutation_rate

        # Calculate improvement rate
        recent_improvement = (
            self._best_fitness_history[-1] - self._best_fitness_history[-2]
        ) / abs(self._best_fitness_history[-2])

        # Calculate population diversity
        current_diversity = helpers.calculate_population_diversity(
            self._current_population
        )

        # Check for stagnation
        if recent_improvement < self.DEFAULT_RESTART_THRESHOLD:
            self._generations_without_improvement += 1
        else:
            self._generations_without_improvement = 0

        # Adjust mutation rate based on both improvement and diversity
        if self._generations_without_improvement >= self.DEFAULT_RESTART_GENERATIONS:
            # Significant stagnation - increase mutation significantly
            return min(self.mutation_rate * 2.5, self.DEFAULT_MAX_MUTATION_RATE)
        elif (
            recent_improvement < 0.01
            and current_diversity < DEFAULT_DIVERSITY_THRESHOLD
        ):
            # Both stagnating and low diversity - increase mutation significantly
            return min(self.mutation_rate * 2.0, self.DEFAULT_MAX_MUTATION_RATE)
        elif recent_improvement < 0.01:
            # Stagnating but good diversity - moderate increase
            return min(self.mutation_rate * 1.5, self.DEFAULT_MAX_MUTATION_RATE)
        elif recent_improvement > 0.05:
            # Good progress - decrease mutation
            return max(self.mutation_rate * 0.8, self.DEFAULT_MIN_MUTATION_RATE)
        return self.mutation_rate
```

**Itamar's Notes:**

**⚠️ Implementation Issue**: The current implementation has a bug where `_best_fitness_history` is initialized but never populated. The code attempts to compare `_best_fitness_history[-1]` (best from current generation) with `_best_fitness_history[-2]` (best from previous generation), but since the list is never appended to, the adaptive mutation logic never executes (it returns early due to `len(self._best_fitness_history) < 2`).

**Expected Behavior**: The code should track the best fitness score from each generation by appending to `_best_fitness_history` after each generation completes, allowing comparison of generation-to-generation improvement (not mutation-to-mutation).

**Fix Required**: After each generation completes (around line 842 in `optimize_prompt`), the code should append the best fitness score from that generation to `_best_fitness_history`. For example:

```python
# After updating best_primary_score_overall (around line 795-808)
self._best_fitness_history.append(best_primary_score_overall)
```

This would allow the adaptive mutation to properly compare generation-to-generation improvement rather than mutation-to-mutation.

**LLM-Based Crossover**: When enabled, uses an LLM to intelligently combine two prompts rather than simple string operations, potentially creating more coherent offspring.

```317:332:sdks/opik_optimizer/src/opik_optimizer/algorithms/evolutionary_optimizer/evolutionary_optimizer.py
                if random.random() < self.crossover_rate:
                    if self.enable_llm_crossover:
                        c1_new, c2_new = crossover_ops.llm_deap_crossover(
                            c1,
                            c2,
                            output_style_guidance=self.output_style_guidance,
                            model=self.model,
                            model_parameters=self.model_parameters,
                            verbose=self.verbose,
                        )
                    else:
                        c1_new, c2_new = crossover_ops.deap_crossover(
                            c1,
                            c2,
                            verbose=self.verbose,
                        )
                    offspring[i], offspring[i + 1] = c1_new, c2_new
                    del offspring[i].fitness.values, offspring[i + 1].fitness.values
```

**Population Restart**: If the population stagnates for multiple generations, it restarts with a new diverse population while preserving the best individuals.

### When to Use

- You have a first draft prompt and want to explore many variations
- You want to balance multiple objectives (e.g., performance vs. prompt length)
- You have sufficient compute budget (this algorithm is computationally expensive)
- You're looking for creative, unexpected prompt improvements

### Implementation Details

The optimizer uses the DEAP (Distributed Evolutionary Algorithms in Python) library for genetic algorithm operations. Each prompt is represented as a DEAP "Individual" containing the prompt messages and metadata.

```207:229:sdks/opik_optimizer/src/opik_optimizer/algorithms/evolutionary_optimizer/evolutionary_optimizer.py
    def _create_individual_from_prompts(
        self, prompts: dict[str, chat_prompt.ChatPrompt]
    ) -> Any:
        """Create a DEAP Individual from a dict of ChatPrompts.

        The Individual content is a dict mapping prompt names to their messages.
        Metadata (tools, function_map) is stored in a 'prompts_metadata' attribute.
        """
        prompts_messages = {name: p.get_messages() for name, p in prompts.items()}
        individual = creator.Individual(prompts_messages)
        setattr(
            individual,
            "prompts_metadata",
            {
                name: {
                    "tools": copy.deepcopy(p.tools),
                    "function_map": p.function_map,
                    "name": p.name,
                }
                for name, p in prompts.items()
            },
        )
        return individual
```

### Default Parameters

- `population_size`: 30 prompts per generation
- `num_generations`: 15 generations
- `mutation_rate`: 0.2 (20% chance of mutation)
- `crossover_rate`: 0.8 (80% chance of crossover)
- `tournament_size`: 4 (for tournament selection)
- `elitism_size`: 3 (preserve top 3 prompts)

---

## FewShotBayesianOptimizer

### Algorithm Overview

The **FewShotBayesianOptimizer** optimizes prompts by automatically selecting the best few-shot examples to include. It uses a two-stage approach:

1. **Template Generation**: Uses an LLM to create a few-shot prompt template
2. **Example Selection**: Uses Bayesian optimization (via Optuna) to find the optimal examples and their count

### How It Works

**Stage 1: Template Generation**

The optimizer first analyzes your prompt and a sample of dataset examples to generate a template for formatting few-shot examples:

```138:202:sdks/opik_optimizer/src/opik_optimizer/algorithms/few_shot_bayesian_optimizer/few_shot_bayesian_optimizer.py
    def _create_fewshot_prompt_template(
        self,
        model: str,
        prompts: dict[str, chat_prompt.ChatPrompt],
        few_shot_examples: list[dict[str, Any]],
    ) -> tuple[dict[str, chat_prompt.ChatPrompt], str]:
        """
        Generate a few-shot prompt template that can be used to insert examples into the prompt.

        Args:
            model: The model to use for generating the template
            prompt: The base prompts to modify
            few_shot_examples: List of example pairs with input and output fields

        Returns:
            A tuple containing the updated prompts and the example template
        """
        # During this step we update the system prompt to include few-shot examples.
        user_message = {
            "prompts": [
                {"name": name, "messages": value.get_messages()}
                for name, value in prompts.items()
            ],
            "examples": few_shot_examples,
        }

        messages: list[dict[str, str]] = [
            {"role": "system", "content": few_shot_prompts.SYSTEM_PROMPT_TEMPLATE},
            {"role": "user", "content": json.dumps(user_message)},
        ]

        # Create dynamic response model with explicit fields for each prompt
        DynamicFewShotPromptMessages = types.create_few_shot_response_model(
            prompt_names=list(prompts.keys())
        )

        logger.debug(f"fewshot_prompt_template - Calling LLM with: {messages}")
        response_content = _llm_calls.call_model(
            messages=messages,
            model=model,
            seed=self.seed,
            model_parameters=self.model_parameters,
            response_model=DynamicFewShotPromptMessages,
        )
        logger.debug(f"fewshot_prompt_template - LLM response: {response_content}")

        new_prompts: dict[str, chat_prompt.ChatPrompt] = {}
        for prompt_name in prompts.keys():
            try:
                # Access field using getattr since field names are dynamic
                messages = [
                    x.model_dump(mode="json")
                    for x in getattr(response_content, prompt_name)
                ]
                new_prompt = prompts[prompt_name].copy()
                new_prompt.set_messages(messages)
            except Exception as e:
                logger.error(
                    f"Couldn't create prompt with placeholder for {prompt_name}: {e}"
                )
                raise

            new_prompts[prompt_name] = new_prompt

        return new_prompts, str(response_content.template)
```

**Stage 2: Bayesian Optimization**

The optimizer uses Optuna's Tree-structured Parzen Estimator (TPE) sampler to search for the best combination of:
- Number of examples (between `min_examples` and `max_examples`)
- Which specific examples to include
- Optionally, which categorical feature combinations to sample from (columnar selection)

```422:528:sdks/opik_optimizer/src/opik_optimizer/algorithms/few_shot_bayesian_optimizer/few_shot_bayesian_optimizer.py
        def optimization_objective(trial: optuna.Trial) -> float:
            n_examples = trial.suggest_int(
                "n_examples", self.min_examples, self.max_examples
            )
            example_indices: list[int] = []
            columnar_choices: list[dict[str, Any]] = []
            selected_indices: set[int] = set()
            for i in range(n_examples):
                selected_index, column_choice = self._suggest_example_index(
                    trial=trial,
                    example_position=i,
                    dataset_size=len(dataset_items),
                    columnar_space=columnar_search_space,
                    selected_indices=selected_indices,
                )
                example_indices.append(selected_index)
                selected_indices.add(selected_index)
                if column_choice:
                    columnar_choices.append(column_choice)
            trial.set_user_attr("example_indices", example_indices)
            if columnar_choices:
                trial.set_user_attr("columnar_choices", columnar_choices)

            # Process few shot examples
            demo_examples = [dataset_items[idx] for idx in example_indices]

            # Build few-shot examples string and processed examples for reporting
            few_shot_examples, processed_demo_examples = (
                self._build_few_shot_examples_string(
                    demo_examples=demo_examples,
                    fewshot_prompt_template=fewshot_prompt_template,
                )
            )

            # Use the helper to build prompts with examples
            prompts_with_examples = self._reconstruct_prompts_with_examples(
                prompts_with_placeholder=prompts,
                demo_examples=demo_examples,
                fewshot_prompt_template=fewshot_prompt_template,
            )

            llm_task = self._build_task_from_messages(
                agent=agent,
                prompts=prompts,
                few_shot_examples=few_shot_examples,
            )

            # Build messages for reporting from the prompts with examples
            messages_for_reporting = []
            for prompt_obj in prompts_with_examples.values():
                messages_for_reporting.extend(prompt_obj.get_messages())

            # Log trial config
            trial_config = copy.deepcopy(base_experiment_config)
            trial_config["configuration"]["prompt"] = (
                messages_for_reporting  # Base instruction
            )
            trial_config["configuration"]["examples"] = (
                processed_demo_examples  # Log stringified examples
            )
            trial_config["configuration"]["n_examples"] = n_examples
            trial_config["configuration"]["example_indices"] = example_indices

            logger.debug(
                f"Trial {trial.number}: n_examples={n_examples}, indices={example_indices}"
            )
            logger.debug(f"Evaluating trial {trial.number}...")

            # Display trial start
            reporting.display_trial_start(
                trial_number=trial.number,
                total_trials=n_trials,
                messages=messages_for_reporting,
                verbose=self.verbose,
            )

            score = task_evaluator.evaluate(
                dataset=evaluation_dataset,  # use right dataset for scoring
                dataset_item_ids=eval_dataset_item_ids,
                metric=metric,
                evaluated_task=llm_task,
                num_threads=self.n_threads,
                project_name=self.project_name,
                experiment_config=trial_config,
                optimization_id=optimization_id,
                verbose=self.verbose,
            )

            # Display trial score
            reporting.display_trial_score(
                trial_number=trial.number,
                baseline_score=baseline_score,
                score=score,
                verbose=self.verbose,
            )
            logger.debug(f"Trial {trial.number} score: {score:.4f}")

            # Trial results
            trial_config = {
                "demo_examples": demo_examples,
                "message_list": messages_for_reporting,
            }
            if columnar_choices:
                trial_config["columnar_choices"] = columnar_choices
            trial.set_user_attr("score", score)
            trial.set_user_attr("config", trial_config)
            return score
```

### Key Features

**Columnar Selection**: When enabled, the optimizer analyzes dataset columns to identify categorical features and groups examples by feature combinations. This allows Optuna to learn which types of examples work best together.

```215:285:sdks/opik_optimizer/src/opik_optimizer/algorithms/few_shot_bayesian_optimizer/few_shot_bayesian_optimizer.py
    def _build_columnar_search_space(
        self, dataset_items: list[dict[str, Any]]
    ) -> ColumnarSearchSpace:
        """
        Infer a lightweight columnar index so Optuna can learn over categorical fields.

        We only keep columns that repeat across rows (avoid high-cardinality text) and
        cap unique values to keep the search space manageable.
        """
        if not dataset_items:
            return ColumnarSearchSpace.empty()

        candidate_columns: list[str] = []
        for key in dataset_items[0]:
            if key == "id":
                continue

            unique_values: set[str] = set()
            skip_column = False
            for item in dataset_items:
                if key not in item:
                    skip_column = True
                    break
                str_value = self._stringify_column_value(item.get(key))
                if str_value is None:
                    skip_column = True
                    break
                unique_values.add(str_value)
                if len(unique_values) > self._MAX_UNIQUE_COLUMN_VALUES:
                    skip_column = True
                    break

            if skip_column:
                continue

            if len(unique_values) < 2 or len(unique_values) >= len(dataset_items):
                continue

            candidate_columns.append(key)

        if not candidate_columns:
            return ColumnarSearchSpace.empty()

        combo_to_indices: dict[str, list[int]] = {}
        for idx, item in enumerate(dataset_items):
            combo_parts: list[str] = []
            skip_example = False
            for column in candidate_columns:
                str_value = self._stringify_column_value(item.get(column))
                if str_value is None:
                    skip_example = True
                    break
                combo_parts.append(f"{column}={str_value}")

            if skip_example:
                continue

            combo_label = "|".join(combo_parts)
            combo_to_indices.setdefault(combo_label, []).append(idx)

        if not combo_to_indices:
            return ColumnarSearchSpace.empty()

        max_group_size = max(len(indices) for indices in combo_to_indices.values())
        combo_labels = sorted(combo_to_indices.keys())
        return ColumnarSearchSpace(
            columns=candidate_columns,
            combo_labels=combo_labels,
            combo_to_indices=combo_to_indices,
            max_group_size=max_group_size,
        )
```

**Diversity Enforcement**: The optimizer encourages diversity within each trial by avoiding duplicate example selections.

```333:361:sdks/opik_optimizer/src/opik_optimizer/algorithms/few_shot_bayesian_optimizer/few_shot_bayesian_optimizer.py
    def _apply_diversity_adjustment(
        self,
        *,
        resolved_index: int,
        selected_indices: set[int],
        dataset_size: int,
        combo_candidates: list[int] | None = None,
        start_offset: int = 0,
    ) -> int:
        """
        Encourage within-trial diversity by steering away from already selected indices.
        """
        if not self.enable_diversity or resolved_index not in selected_indices:
            return resolved_index

        if combo_candidates:
            for offset in range(len(combo_candidates)):
                candidate = combo_candidates[
                    (start_offset + offset) % len(combo_candidates)
                ]
                if candidate not in selected_indices:
                    return candidate

        for offset in range(dataset_size):
            candidate = (resolved_index + offset) % dataset_size
            if candidate not in selected_indices:
                return candidate

        return resolved_index
```

**Optuna Pruning**: Uses median pruning to stop unpromising trials early, saving compute.

### When to Use

- You have a well-defined task that benefits from examples
- You want to automatically find the optimal number of examples
- Your dataset has diverse examples that need careful selection
- You want a more efficient optimization than evolutionary approaches

**Itamar's Notes:**

**⚠️ Limited Use Case**: FewShotBayesianOptimizer should only be used in **rare cases** where you want to train from "good examples" rather than having "evals" that define good behavior. 

In this specific use case, you need to maintain a clear concept of testing/validation dataset:
- The training dataset contains the "good examples" that the optimizer selects from
- The validation dataset is used to evaluate whether the selected examples actually improve performance on unseen data
- This separation is critical to prevent overfitting to the training examples

For most other optimization scenarios where you have proper evaluation metrics that define good behavior, a validation dataset may not be needed, and other optimizers (like MetaPromptOptimizer or HierarchicalReflectiveOptimizer) may be more appropriate.

### Implementation Details

The optimizer uses Optuna's TPE (Tree-structured Parzen Estimator) sampler, which is particularly effective for hyperparameter optimization. It maintains a probabilistic model of the search space and uses it to suggest promising parameter combinations.

```544:554:sdks/opik_optimizer/src/opik_optimizer/algorithms/few_shot_bayesian_optimizer/few_shot_bayesian_optimizer.py
        # Explicitly create and seed the sampler for Optuna
        sampler = optuna.samplers.TPESampler(
            seed=self.seed, multivariate=self.enable_multivariate_tpe
        )
        pruner = (
            optuna.pruners.MedianPruner(n_startup_trials=3)
            if self.enable_optuna_pruning
            else optuna.pruners.NopPruner()
        )
        study = optuna.create_study(
            direction="maximize", sampler=sampler, pruner=pruner
        )
```

### Default Parameters

- `min_examples`: 2
- `max_examples`: 8
- `n_threads`: 8
- `enable_columnar_selection`: True
- `enable_multivariate_tpe`: True
- `enable_optuna_pruning`: True

---

## GepaOptimizer

### Algorithm Overview

The **GepaOptimizer** uses the GEPA (Genetic-Pareto) algorithm, which combines genetic algorithms with Pareto optimization. It's designed to handle complex optimization tasks where you want to balance multiple objectives.

### How It Works

GEPA is an external library that the optimizer wraps. The algorithm:

1. **Initialization**: Starts with a seed candidate (your initial prompt)
2. **Generation**: Generates new candidates through genetic operations
3. **Evaluation**: Evaluates candidates on training and validation sets
4. **Pareto Selection**: Maintains a Pareto front of non-dominated solutions
5. **Reflection**: Uses an LLM to reflect on failures and generate improvements
6. **Merge Operations**: Optionally merges successful candidates

The optimizer acts as an adapter between Opik's evaluation framework and the GEPA library:

```373:424:sdks/opik_optimizer/src/opik_optimizer/algorithms/gepa_optimizer/gepa_optimizer.py
            # Create the adapter with multi-prompt support
            adapter = OpikGEPAAdapter(
                base_prompts=optimizable_prompts,
                agent=self.agent,
                optimizer=self,
                metric=metric,
                dataset=dataset,
                experiment_config=experiment_config,
            )

            try:
                import gepa
                import inspect
            except Exception as exc:  # pragma: no cover
                raise ImportError("gepa package is required for GepaOptimizer") from exc

            # When using our Rich logger, disable GEPA's native progress bar to avoid conflicts
            use_gepa_progress_bar = display_progress_bar if self.verbose == 0 else False

            with gepa_reporting.start_gepa_optimization(
                verbose=self.verbose, max_trials=max_trials
            ) as reporter:
                # Create logger with progress bar support
                logger_instance = gepa_reporting.RichGEPAOptimizerLogger(
                    self,
                    verbose=self.verbose,
                    progress=reporter.progress,
                    task_id=reporter.task_id,
                    max_trials=max_trials,
                )

                kwargs_gepa: dict[str, Any] = {
                    "seed_candidate": seed_candidate,
                    "trainset": train_insts,
                    "valset": val_insts,
                    "adapter": adapter,
                    "task_lm": None,
                    "reflection_lm": self.model,
                    "candidate_selection_strategy": candidate_selection_strategy,
                    "skip_perfect_score": skip_perfect_score,
                    "reflection_minibatch_size": reflection_minibatch_size,
                    "perfect_score": perfect_score,
                    "use_merge": use_merge,
                    "max_merge_invocations": max_merge_invocations,
                    "max_metric_calls": max_metric_calls,
                    "run_dir": run_dir,
                    "track_best_outputs": track_best_outputs,
                    "display_progress_bar": use_gepa_progress_bar,
                    "seed": seed,
                    "raise_on_exception": raise_on_exception,
                    "logger": logger_instance,
                }
```

### Key Features

**Multi-Prompt Support**: The optimizer can optimize multiple prompts simultaneously, treating each message component as a separate optimization variable.

```236:252:sdks/opik_optimizer/src/opik_optimizer/algorithms/gepa_optimizer/gepa_optimizer.py
        # Build multi-component seed_candidate from all messages in all prompts
        seed_candidate: dict[str, str] = {}
        for prompt_name, prompt_obj in optimizable_prompts.items():
            messages = prompt_obj.get_messages()
            for idx, msg in enumerate(messages):
                component_key = f"{prompt_name}_{msg['role']}_{idx}"
                content = msg.get("content", "")
                # Handle content that might be a list (multimodal)
                if isinstance(content, list):
                    # Extract text from content parts
                    text_parts = [
                        part.get("text", "")
                        for part in content
                        if isinstance(part, dict) and part.get("type") == "text"
                    ]
                    content = " ".join(text_parts)
                seed_candidate[component_key] = str(content)
```

**Reflection-Based Improvement**: Uses an LLM to reflect on failure cases and generate targeted improvements, making it more intelligent than pure genetic operations.

**Validation Dataset Support**: Can use a separate validation dataset to prevent overfitting, evaluating candidates on both training and validation sets.

**Pareto Optimization**: Maintains a set of non-dominated solutions, allowing you to choose based on multiple criteria.

### When to Use

- You need to optimize complex, multi-component prompts
- You want to balance multiple objectives (Pareto optimization)
- You have a validation dataset to prevent overfitting
- You want reflection-based improvements rather than random mutations

### Implementation Details

The optimizer converts Opik datasets and prompts into GEPA's internal format, runs the optimization, then converts results back to Opik format. It also rescores all candidates using Opik's evaluation framework to ensure consistency.

```457:540:sdks/opik_optimizer/src/opik_optimizer/algorithms/gepa_optimizer/gepa_optimizer.py
        candidates: list[dict[str, str]] = getattr(gepa_result, "candidates", []) or []
        val_scores: list[float] = list(getattr(gepa_result, "val_aggregate_scores", []))

        # Filter duplicate candidates based on content
        indexed_candidates: list[tuple[int, dict[str, str]]] = list(
            enumerate(candidates)
        )
        filtered_indexed_candidates = unique_ordered_by_key(
            indexed_candidates,
            key=lambda item: str(sorted(item[1].items())),
        )
        filtered_candidates: list[dict[str, str]] = [
            candidate for _, candidate in filtered_indexed_candidates
        ]
        filtered_val_scores: list[float | None] = [
            val_scores[idx] if idx < len(val_scores) else None
            for idx, _ in filtered_indexed_candidates
        ]

        rescored: list[float] = []
        candidate_rows: list[dict[str, Any]] = []
        history: list[dict[str, Any]] = []

        # Wrap rescoring to prevent OPIK messages and experiment link displays
        with suppress_opik_logs():
            with convert_tqdm_to_rich(verbose=0):
                for idx, (original_idx, candidate) in enumerate(
                    filtered_indexed_candidates
                ):
                    # Rebuild prompts from candidate
                    prompt_variants = self._rebuild_prompts_from_candidate(
                        optimizable_prompts, candidate
                    )

                    try:
                        # Use base class evaluate_prompt which handles dict prompts
                        score = self.evaluate_prompt(
                            prompt=prompt_variants,
                            dataset=dataset,
                            metric=metric,
                            agent=self.agent,
                            n_samples=n_samples,
                            verbose=0,
                        )
                        score = float(score)
                    except Exception:
                        logger.debug(
                            "Rescoring failed for candidate %s", idx, exc_info=True
                        )
                        score = 0.0

                    rescored.append(score)
                    # Get a summary text for display (backward compatible)
                    candidate_summary_text = self._get_candidate_summary_text(
                        candidate, optimizable_prompts
                    )
                    candidate_rows.append(
                        {
                            "iteration": idx + 1,
                            "system_prompt": candidate_summary_text,
                            "gepa_score": filtered_val_scores[idx],
                            "opik_score": score,
                            "source": self.__class__.__name__,
                            "components": {
                                k: v
                                for k, v in candidate.items()
                                if not k.startswith("_") and k not in ("source", "id")
                            },
                        }
                    )
                    history.append(
                        {
                            "iteration": idx + 1,
                            "prompt_candidate": candidate,
                            "scores": [
                                {
                                    "metric_name": f"GEPA-{metric.__name__}",
                                    "score": filtered_val_scores[idx],
                                },
                                {"metric_name": metric.__name__, "score": score},
                            ],
                            "metadata": {},
                        }
                    )
```

### Default Parameters

- `n_threads`: 6
- `max_trials`: 10
- `reflection_minibatch_size`: 3
- `candidate_selection_strategy`: "pareto"
- `skip_perfect_score`: True
- `use_merge`: False

---

## MetaPromptOptimizer

### Algorithm Overview

The **MetaPromptOptimizer** uses LLM-based meta-reasoning to iteratively improve prompts. It analyzes prompt performance and uses an LLM to reason about what changes would be most effective.

### How It Works

The algorithm follows an iterative refinement loop:

1. **Evaluate Current Prompt**: Runs the current prompt on the dataset
2. **Analyze Performance**: Uses an LLM to analyze successes and failures
3. **Generate Candidates**: Creates multiple candidate prompt variations based on the analysis
4. **Evaluate Candidates**: Tests all candidates on the dataset
5. **Select Best**: Chooses the best-performing candidate
6. **Repeat**: Continues until max_trials is reached or performance plateaus

### Key Features

**Context-Aware Reasoning**: The optimizer can include task-specific context (dataset examples, metric understanding) to help the LLM make better improvement decisions.

```654:664:sdks/opik_optimizer/src/opik_optimizer/algorithms/meta_prompt_optimizer/meta_prompt_optimizer.py
    def _get_task_context(self, metric: MetricFunction) -> tuple[str, int]:
        """Get task-specific context from the dataset and metric (delegates to ops)."""
        return context_ops.get_task_context(
            dataset=self.dataset,
            metric=metric,
            num_examples=self.num_task_examples,
            columns=self.task_context_columns,
            max_tokens=self.max_context_tokens,
            model=self.model,
            extract_metric_understanding=self.extract_metric_understanding,
        )
```

**Hall of Fame Pattern Mining**: Maintains a "Hall of Fame" of best-performing prompts and periodically extracts common patterns to inject into new candidates.

```449:464:sdks/opik_optimizer/src/opik_optimizer/algorithms/meta_prompt_optimizer/meta_prompt_optimizer.py
                # Check if we should extract patterns from hall of fame
                if self.hall_of_fame and self.hall_of_fame.should_extract_patterns(
                    trials_used
                ):
                    logger.info(
                        f"Extracting patterns from hall of fame at trial {trials_used}"
                    )
                    new_patterns = self.hall_of_fame.extract_patterns(
                        model=self.model,
                        model_parameters=self.model_parameters,
                        metric_name=metric.__name__,
                    )
                    if new_patterns:
                        logger.info(f"Extracted {len(new_patterns)} new patterns")
                        for i, pattern in enumerate(new_patterns[:3], 1):
                            logger.debug(f"  Pattern {i}: {pattern[:100]}...")
```

**History Context**: Uses previous optimization rounds to inform new candidate generation, learning from what worked and what didn't.

```688:698:sdks/opik_optimizer/src/opik_optimizer/algorithms/meta_prompt_optimizer/meta_prompt_optimizer.py
    def _build_history_context(self, previous_rounds: list[OptimizationRound]) -> str:
        """Build context from Hall of Fame and previous optimization rounds."""
        top_prompts_to_show = max(
            self.prompts_per_round, self.synthesis_prompts_per_round
        )
        return context_ops.build_history_context(
            previous_rounds,
            hall_of_fame=self.hall_of_fame if hasattr(self, "hall_of_fame") else None,
            pretty_mode=self.prettymode_prompt_history,
            top_prompts_per_round=top_prompts_to_show,
        )
```

**Synthesis Prompts**: Periodically generates "synthesis" prompts that combine elements from top performers, potentially discovering new effective patterns.

### When to Use

- You want LLM-guided improvements rather than random exploration
- You want the optimizer to reason about best practices
- You need prompts that follow specific patterns or guidelines
- You want iterative refinement with clear reasoning

### Implementation Details

The optimizer generates candidates by calling an LLM with:
- The current prompt and its performance
- Task context (dataset examples, metric understanding)
- History of previous rounds
- Hall of Fame patterns (if enabled)
- Instructions for generating improvements

```471:487:sdks/opik_optimizer/src/opik_optimizer/algorithms/meta_prompt_optimizer/meta_prompt_optimizer.py
                try:
                    bundle_candidates = candidate_ops.generate_agent_bundle_candidates(
                        optimizer=self,
                        current_prompts=best_prompts,
                        best_score=best_score,
                        round_num=round_num,
                        previous_rounds=rounds,
                        metric=metric,
                        optimization_id=optimization_id,
                        project_name=self.project_name,
                        build_history_context_fn=self._build_history_context,
                        get_task_context_fn=self._get_task_context,
                    )
                    # Extract prompts from bundle candidates and limit to prompts_this_round
                    candidate_prompts = [
                        bundle.prompts
                        for bundle in bundle_candidates[:prompts_this_round]
                    ]

                except Exception as e:
```

### Default Parameters

- `prompts_per_round`: 4
- `enable_context`: True
- `num_task_examples`: 5
- `n_threads`: 12
- `use_hall_of_fame`: True
- `hall_of_fame_size`: 10
- `pattern_extraction_interval`: 5

---

## HierarchicalReflectiveOptimizer

### Algorithm Overview

The **HierarchicalReflectiveOptimizer** uses hierarchical root cause analysis to systematically identify and address failure modes in prompts. It's designed for systematic, targeted improvements.

### How It Works

The algorithm follows a structured approach:

1. **Evaluate Prompt**: Runs the current prompt on the dataset
2. **Hierarchical Root Cause Analysis**: 
   - Splits evaluation results into batches
   - Analyzes each batch to identify failure patterns
   - Synthesizes batch analyses into unified failure modes
3. **Generate Improvements**: For each failure mode, generates an improved prompt
4. **Evaluate Improvements**: Tests each improvement
5. **Iterate**: Repeats until convergence or max_trials reached

### Key Features

**Hierarchical Analysis**: Uses a two-stage analysis process:
- **Batch Analysis**: Analyzes failures in small batches to identify local patterns
- **Synthesis**: Combines batch analyses to find unified failure modes

```114:133:sdks/opik_optimizer/src/opik_optimizer/algorithms/hierarchical_reflective_optimizer/hierarchical_reflective_optimizer.py
    def _hierarchical_root_cause_analysis(
        self, evaluation_result: EvaluationResult
    ) -> HierarchicalRootCauseAnalysis:
        """
        Perform hierarchical root cause analysis on evaluation results.

        This method uses a two-stage hierarchical approach:
        1. Split results into batches and analyze each batch
        2. Synthesize batch analyses into unified failure modes

        Args:
            evaluation_result: The evaluation result to analyze

        Returns:
            HierarchicalRootCauseAnalysis containing batch analyses and overall synthesis
        """
        logger.debug("Performing hierarchical root cause analysis...")
        return self._hierarchical_analyzer.analyze(
            evaluation_result, project_name=self.project_name
        )
```

**Targeted Improvements**: Each improvement addresses a specific, identified failure mode rather than making general changes.

```135:200:sdks/opik_optimizer/src/opik_optimizer/algorithms/hierarchical_reflective_optimizer/hierarchical_reflective_optimizer.py
    def _improve_prompt(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        root_cause: FailureMode,
        attempt: int = 1,
    ) -> dict[str, ImprovedPrompt]:
        """
        Improve all prompts in the dict based on the root cause analysis.

        Makes a single LLM call to improve all prompts at once for efficiency.

        Args:
            prompts: Dictionary of prompts to improve
            root_cause: The failure mode to address
            attempt: Attempt number (1-indexed). Used to vary seed for retries.

        Returns:
            Dictionary mapping prompt names to ImprovedPrompt objects
        """
        # Format all prompts into a single section
        prompts_section = ""
        for prompt_name, prompt in prompts.items():
            prompts_section += f"\n--- Prompt: {prompt_name} ---\n"
            prompts_section += f"```\n{prompt.get_messages()}\n```\n"

        improve_prompt_prompt = IMPROVE_PROMPT_TEMPLATE.format(
            prompts_section=prompts_section,
            failure_mode_name=root_cause.name,
            failure_mode_description=root_cause.description,
            failure_mode_root_cause=root_cause.root_cause,
        )

        # Vary seed based on attempt to avoid cache hits and ensure different results
        # Each attempt gets a unique seed: base_seed, base_seed+1000, base_seed+2000, etc.
        attempt_seed = self.seed + (attempt - 1) * 1000

        if attempt > 1:
            logger.debug(
                f"Retry attempt {attempt}: Using seed {attempt_seed} (base seed: {self.seed})"
            )

        # Create dynamic response model for all prompts
        from . import types as hierarchical_types

        DynamicImprovedPromptsResponse = (
            hierarchical_types.create_improved_prompts_response_model(
                prompt_names=list(prompts.keys())
            )
        )

        improve_prompt_response = _llm_calls.call_model(
            messages=[{"role": "user", "content": improve_prompt_prompt}],
            model=self.model,
            seed=attempt_seed,
            model_parameters=self.model_parameters,
            response_model=DynamicImprovedPromptsResponse,
        )

        # Extract improved prompts from response
        improved_prompts = {}
        for prompt_name in prompts.keys():
            improved_prompts[prompt_name] = getattr(
                improve_prompt_response, prompt_name
            )

        return improved_prompts
```

**Retry Mechanism**: If an improvement doesn't help, the optimizer can retry with a different seed to generate alternative improvements.

**Convergence Detection**: Stops when improvements fall below a threshold, indicating the prompt has reached a local optimum.

```580:596:sdks/opik_optimizer/src/opik_optimizer/algorithms/hierarchical_reflective_optimizer/hierarchical_reflective_optimizer.py
            # Check for convergence after iteration
            iteration_improvement = self._calculate_improvement(
                best_score, previous_iteration_score
            )

            logger.info(
                f"Iteration {iteration} complete. Score: {best_score:.4f}, "
                f"Improvement: {iteration_improvement:.2%}"
            )

            # Stop if improvement is below convergence threshold
            if abs(iteration_improvement) < self.convergence_threshold:
                logger.info(
                    f"Convergence achieved: improvement ({iteration_improvement:.2%}) "
                    f"below threshold ({self.convergence_threshold:.2%}). "
                    f"Stopping after {iteration} iterations."
                )
                break
```

### When to Use

- You have a complex prompt with multiple potential failure modes
- You want systematic, targeted improvements rather than exploration
- You need to understand why your prompt fails
- You want to address specific issues one at a time

### Implementation Details

The optimizer uses a separate `HierarchicalRootCauseAnalyzer` class that handles the batch analysis and synthesis. This separation keeps the analysis logic modular and testable.

```78:86:sdks/opik_optimizer/src/opik_optimizer/algorithms/hierarchical_reflective_optimizer/hierarchical_reflective_optimizer.py
        # Initialize hierarchical analyzer
        self._hierarchical_analyzer = HierarchicalRootCauseAnalyzer(
            reasoning_model=self.model,
            seed=self.seed,
            max_parallel_batches=self.max_parallel_batches,
            batch_size=self.batch_size,
            verbose=self.verbose,
            model_parameters=self.model_parameters,
        )
```

### Default Parameters

- `max_parallel_batches`: 5
- `batch_size`: 25
- `convergence_threshold`: 0.01 (1% improvement)
- `n_threads`: 12
- `max_retries`: 2

---

## ParameterOptimizer

### Algorithm Overview

The **ParameterOptimizer** optimizes LLM call parameters (temperature, top_p, etc.) rather than prompt content. It uses Bayesian optimization to find the best parameter settings.

### How It Works

The optimizer:

1. **Defines Search Space**: Takes a `ParameterSearchSpace` defining which parameters to optimize and their ranges
2. **Baseline Evaluation**: Evaluates the prompt with default parameters
3. **Global Search**: Uses Optuna's TPE sampler to explore the full parameter space
4. **Local Search**: Optionally refines around the best parameters found
5. **Returns Best Parameters**: Returns the prompt with optimized parameters applied

### Key Features

**Multi-Prompt Support**: When optimizing multiple prompts, can optimize parameters independently for each prompt or share parameters across prompts.

```200:202:sdks/opik_optimizer/src/opik_optimizer/algorithms/parameter_optimizer/parameter_optimizer.py
        # Expand parameter space for all prompts
        prompt_names = list(prompts.keys())
        expanded_parameter_space = parameter_space.expand_for_prompts(prompt_names)
```

**Two-Phase Search**: 
- **Global Phase**: Explores the full parameter space
- **Local Phase**: Refines around the best parameters found (narrows search space)

```392:515:sdks/opik_optimizer/src/opik_optimizer/algorithms/parameter_optimizer/parameter_optimizer.py
        if global_trials > 0:
            if self.verbose >= 1:
                from rich.text import Text
                from rich.console import Console

                console = Console()
                console.print("")
                console.print(Text("> Starting global search phase", style="bold cyan"))
                console.print(
                    Text(
                        f"│ Exploring full parameter space with {global_trials} trials"
                    )
                )
                console.print("")

            study.optimize(
                objective,
                n_trials=global_trials,
                timeout=timeout,
                callbacks=callbacks,
                show_progress_bar=False,
            )

        for trial in study.trials:
            if trial.state != TrialState.COMPLETE or trial.value is None:
                continue
            timestamp = (
                trial.datetime_complete
                or trial.datetime_start
                or datetime.now(timezone.utc)
            )
            history.append(
                {
                    "iteration": trial.number + 1,
                    "timestamp": timestamp.isoformat(),
                    "parameters": trial.user_attrs.get("parameters", {}),
                    "score": float(trial.value),
                    "model_kwargs": trial.user_attrs.get("model_kwargs"),
                    "model": trial.user_attrs.get("model"),
                    "stage": trial.user_attrs.get("stage", "global"),
                }
            )

        best_score = baseline_score
        best_parameters: dict[str, Any] = {}
        best_model_kwargs: dict[str, Any] = {
            name: copy.deepcopy(p.model_kwargs or {})
            for name, p in base_prompts.items()
        }
        best_model: dict[str, str] = {name: p.model for name, p in base_prompts.items()}

        completed_trials = [
            trial
            for trial in study.trials
            if trial.state == TrialState.COMPLETE and trial.value is not None
        ]
        if completed_trials:
            best_trial = max(completed_trials, key=lambda t: t.value)  # type: ignore[arg-type]
            if best_trial.value is not None and best_trial.value > best_score:
                best_score = float(best_trial.value)
                best_parameters = best_trial.user_attrs.get("parameters", {})
                best_model_kwargs = best_trial.user_attrs.get("model_kwargs", {})
                best_model = best_trial.user_attrs.get("model", best_model)

        local_space: ParameterSearchSpace | None = None
        if (
            local_trials > 0
            and completed_trials
            and any(
                spec.distribution in {ParameterType.FLOAT, ParameterType.INT}
                for spec in expanded_parameter_space.parameters
            )
        ):
            local_scale = (
                self.local_search_scale
                if local_search_scale_override is None
                else max(0.0, float(local_search_scale_override))
            )

            if best_parameters:
                center_values = best_parameters
            else:
                center_values = {}

            if local_scale > 0 and center_values:
                current_stage = "local"
                local_space = expanded_parameter_space.narrow_around(
                    center_values, local_scale
                )
                local_range = local_space.describe()
                stage_records.append(
                    {
                        "stage": "local",
                        "trials": local_trials,
                        "scale": local_scale,
                        "parameters": local_range,
                    }
                )
                search_ranges["local"] = local_range

                if self.verbose >= 1:
                    from rich.text import Text
                    from rich.console import Console

                    console = Console()
                    console.print("")
                    console.print(
                        Text("> Starting local search phase", style="bold cyan")
                    )
                    console.print(
                        Text(
                            f"│ Refining around best parameters with {local_trials} trials (scale: {local_scale})"
                        )
                    )
                    console.print("")

                current_space = local_space
                study.optimize(
                    objective,
                    n_trials=local_trials,
                    timeout=timeout,
                    callbacks=callbacks,
                    show_progress_bar=False,
                )
```

**Parameter Importance Analysis**: After optimization, analyzes which parameters had the most impact on performance.

```565:576:sdks/opik_optimizer/src/opik_optimizer/algorithms/parameter_optimizer/parameter_optimizer.py
        try:
            importance = optuna_importance.get_param_importances(study)
        except (ValueError, RuntimeError, ImportError):
            # Falls back to custom sensitivity analysis if:
            # - Study has insufficient data (ValueError/RuntimeError)
            # - scikit-learn not installed (ImportError)
            importance = {}

        if not importance or all(value == 0 for value in importance.values()):
            importance = compute_sensitivity_from_trials(
                completed_trials, expanded_parameter_space.parameters
            )
```

### When to Use

- You have a good prompt but want to fine-tune model behavior
- You want to optimize temperature, top_p, or other generation parameters
- You don't want to modify prompt content
- You want faster optimization than prompt-based methods

### Implementation Details

The optimizer uses a `ParameterSearchSpace` object to define which parameters to optimize. This supports:
- Continuous parameters (float): temperature, top_p, etc.
- Discrete parameters (int): max_tokens, etc.
- Categorical parameters: model selection, etc.

Parameters are applied to prompts via the `model_kwargs` field, which is passed to LiteLLM during evaluation.

```327:379:sdks/opik_optimizer/src/opik_optimizer/algorithms/parameter_optimizer/parameter_optimizer.py
        def objective(trial: Trial) -> float:
            nonlocal current_best_score, best_tuned_prompts

            sampled_values = current_space.suggest(trial)

            # Apply parameters to all prompts using the expanded space
            tuned_prompts = current_space.apply_to_prompts(
                base_prompts,
                sampled_values,
                base_model_kwargs=base_model_kwargs,
            )

            # Display trial evaluation with parameters
            with reporting.display_trial_evaluation(
                trial_number=trial.number,
                total_trials=total_trials,
                stage=current_stage,
                parameters=sampled_values,
                verbose=self.verbose,
            ) as trial_reporter:
                score = self.evaluate_prompt(
                    prompt=tuned_prompts,
                    agent=agent,
                    dataset=evaluation_dataset,
                    metric=metric,
                    n_threads=self.n_threads,
                    verbose=self.verbose,
                    experiment_config=experiment_config,
                    n_samples=n_samples,
                )

                # Check if this is a new best
                is_best = score > current_best_score
                if is_best:
                    current_best_score = score
                    best_tuned_prompts = copy.deepcopy(tuned_prompts)

                trial_reporter.set_score(score, is_best=is_best)

            # Store per-prompt model_kwargs in trial attrs
            trial.set_user_attr("parameters", sampled_values)
            trial.set_user_attr(
                "model_kwargs",
                {
                    name: copy.deepcopy(p.model_kwargs)
                    for name, p in tuned_prompts.items()
                },
            )
            trial.set_user_attr(
                "model", {name: p.model for name, p in tuned_prompts.items()}
            )
            trial.set_user_attr("stage", current_stage)
            return float(score)
```

### Default Parameters

- `default_n_trials`: 20
- `local_search_ratio`: 0.3 (30% of trials for local search)
- `local_search_scale`: 0.2 (narrow search space to 20% around best)
- `n_threads`: 4

---

## Algorithm Comparison

| Optimizer | Algorithm Type | Best For | Speed | Exploration | Exploitation |
|-----------|---------------|---------|-------|-------------|--------------|
| **EvolutionaryOptimizer** | Genetic Algorithm | Creative exploration, multi-objective | Slow | High | Medium |
| **FewShotBayesianOptimizer** | Bayesian Optimization | Few-shot example selection | Medium | Medium | High |
| **GepaOptimizer** | Genetic-Pareto | Complex multi-component prompts | Slow | High | High |
| **MetaPromptOptimizer** | LLM Meta-Reasoning | Guided improvements, best practices | Medium | Low | High |
| **HierarchicalReflectiveOptimizer** | Root Cause Analysis | Systematic failure mode fixes | Medium | Low | Very High |
| **ParameterOptimizer** | Bayesian Optimization | Parameter tuning | Fast | Medium | High |

### Exploration vs. Exploitation

- **Exploration**: How much the algorithm explores new, untested areas of the search space
- **Exploitation**: How much the algorithm refines and improves known good solutions

**High Exploration**: EvolutionaryOptimizer, GepaOptimizer
- Good for discovering unexpected improvements
- May find creative solutions
- Can be slower and less focused

**High Exploitation**: HierarchicalReflectiveOptimizer, MetaPromptOptimizer
- Good for systematic refinement
- More targeted improvements
- Faster convergence on good solutions

**Balanced**: FewShotBayesianOptimizer, ParameterOptimizer
- Good general-purpose choices
- Efficient search strategies
- Good balance of exploration and refinement

---

## Choosing the Right Optimizer

### Decision Tree

1. **Do you want to optimize parameters (temperature, top_p) or prompt content?**
   - **Parameters** → Use `ParameterOptimizer`
   - **Content** → Continue to step 2

2. **Do you want to add few-shot examples to your prompt?**
   - **Yes** → Use `FewShotBayesianOptimizer`
   - **No** → Continue to step 3

3. **What's your optimization goal?**
   - **Systematic fixes for known issues** → Use `HierarchicalReflectiveOptimizer`
   - **Guided improvements with reasoning** → Use `MetaPromptOptimizer`
   - **Creative exploration, many variations** → Use `EvolutionaryOptimizer`
   - **Complex multi-component optimization** → Use `GepaOptimizer`

### Use Case Examples

**Example 1: Quick Parameter Tuning**
```python
from opik_optimizer import ParameterOptimizer, ParameterSearchSpace

optimizer = ParameterOptimizer(model="gpt-4o")
space = ParameterSearchSpace(
    parameters={
        "temperature": ParameterSpec(type=ParameterType.FLOAT, min=0.0, max=2.0),
        "top_p": ParameterSpec(type=ParameterType.FLOAT, min=0.0, max=1.0),
    }
)
result = optimizer.optimize_parameter(
    prompt=prompt,
    dataset=dataset,
    metric=metric,
    parameter_space=space,
    max_trials=20
)
```

**Example 2: Adding Few-Shot Examples**
```python
from opik_optimizer import FewShotBayesianOptimizer

optimizer = FewShotBayesianOptimizer(
    model="gpt-4o-mini",
    min_examples=3,
    max_examples=8
)
result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=metric,
    max_trials=30
)
```

**Example 3: Systematic Improvement**
```python
from opik_optimizer import HierarchicalReflectiveOptimizer

optimizer = HierarchicalReflectiveOptimizer(
    model="gpt-4o",
    convergence_threshold=0.01
)
result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=metric,
    max_trials=10
)
```

**Example 4: Creative Exploration**
```python
from opik_optimizer import EvolutionaryOptimizer

optimizer = EvolutionaryOptimizer(
    model="gpt-4o",
    population_size=30,
    num_generations=15,
    enable_moo=True  # Balance performance and prompt length
)
result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=metric,
    max_trials=100
)
```

### Chaining Optimizers

You can chain optimizers together, using the output of one as input to another:

```python
# First, optimize few-shot examples
few_shot_optimizer = FewShotBayesianOptimizer(model="gpt-4o-mini")
few_shot_result = few_shot_optimizer.optimize_prompt(
    prompt=initial_prompt,
    dataset=dataset,
    metric=metric,
    max_trials=20
)

# Then, refine the prompt with meta-reasoning
meta_optimizer = MetaPromptOptimizer(model="gpt-4o")
final_result = meta_optimizer.optimize_prompt(
    prompt=ChatPrompt.from_result(few_shot_result),  # Use previous result
    dataset=dataset,
    metric=metric,
    max_trials=10
)
```

---

## Conclusion

Each optimizer in the Opik Optimizer SDK is designed for specific use cases and optimization strategies. Understanding their algorithms, strengths, and implementation details will help you choose the right optimizer for your task and get the best results.

For more information, see:
- [Opik Optimizer README](README.md)
- [Architecture Documentation](.cursor/rules/architecture.mdc)
- [Code Structure Guidelines](.cursor/rules/code-structure.mdc)
