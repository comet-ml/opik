from typing import Any, Callable, Dict, List, Literal, Optional, Tuple

import random
import logging

import opik
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer import (
    task_evaluator,
)

logger = logging.getLogger(__name__)


class LangGraphOptimizer(BaseOptimizer):
    def __init__(self, project_name, llm, num_threads, tags=None):
        self.project_name = project_name
        self.llm = llm
        self.num_threads = num_threads
        self.tags = tags

    def optimize_prompt(self, dataset, metric_config, task_config, n_samples):
        self._opik_client = opik.Opik()
        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric_config.metric.name,
                metadata={"optimizer": self.__class__.__name__},
            )
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            optimization = None

        if not optimization:
            logger.warning("Continuing without Opik optimization tracking.")

        self.tools = task_config.tools
        prompt_template = task_config.instruction_prompt

        agent_config = {
            "prompts": [prompt_template],
            "tools": self.tools,
        }

        # Call user's method:
        graph = self.build_agent(agent_config)

        dataset_item_ids = [
            item["id"] for item in random.sample(dataset.get_items(), n_samples)
        ]

        program_task = self.get_program_task(graph, task_config)

        experiment_config = {
            "optimizer": self.__class__.__name__,
            "tools": (
                [f["name"] for f in task_config.tools] if task_config.tools else []
            ),
            "metric": metric_config.metric.name,
            "dataset": dataset.name,
            "configuration": {
                "prompt": prompt_template,
            },
            "evaluation": "initial",
        }
        print("Initial prompt:")
        print(prompt_template)
        score = task_evaluator.evaluate(
            dataset=dataset,
            evaluated_task=program_task,
            metric_config=metric_config,
            dataset_item_ids=dataset_item_ids,
            project_name=self.project_name,
            num_threads=self.num_threads,
            experiment_config=experiment_config,
            optimization_id=optimization.id,
        )

        experiment_config["evaluation"] = "full"
        count = 0
        while count < 3:
            response = self.llm.invoke(
                """Refine this prompt template to make it better. Just give me the better prompt, nothing else. 

The new prompt must contain {tools}, {agent_scratchpad}, {input}, and [{tool_names}]

Suggest things like keeping the answer brief. The answers should be like answers
to a trivia question.

Here is the prompt: 

%r
"""
                % prompt_template
            )
            new_prompt = response.content.replace("\\n", "\n")
            if "{input}" not in new_prompt:
                new_prompt += "\nQuestion: {input}"
            if "{agent_scratchpad}" not in new_prompt:
                new_prompt += "\nThought: {agent_scratchpad}"
            if "[{tool_names}]" not in new_prompt:
                new_prompt += "\nTool names: [{tool_names}]"
            if "{tools}" not in new_prompt:
                new_prompt += "\nTools: {tools}"

            experiment_config["configuration"]["prompt"] = new_prompt

            print(new_prompt)

            agent_config = {
                "prompts": [new_prompt],
                "tools": self.tools,
            }

            try:
                # Calls user subclass method:
                graph = self.build_agent(agent_config)
            except Exception as exc:
                print(new_prompt)
                print("Failed to create a graph %r; trying again..." % exc)
                continue

            count += 1
            score = task_evaluator.evaluate(
                dataset=dataset,
                evaluated_task=program_task,
                metric_config=metric_config,
                dataset_item_ids=dataset_item_ids,
                project_name=self.project_name,
                num_threads=self.num_threads,
                experiment_config=experiment_config,
                optimization_id=optimization.id,
            )

        if optimization:
            self.update_optimization(optimization, status="completed")
