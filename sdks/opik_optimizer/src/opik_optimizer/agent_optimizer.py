from typing import Any, Callable, Dict, List, Literal, Optional, Tuple

import random
import logging

import opik
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer import (
    task_evaluator,
)

logger = logging.getLogger(__name__)


class OpikAgent:
    def __init__(self, optimizer, agent_config):
        self.optimizer = optimizer
        self.init_agent(agent_config)


class OpikAgentOptimizer(BaseOptimizer):
    def __init__(self, project_name, agent_class, tags=None):
        self.project_name = project_name
        self.agent_class = agent_class
        self.tags = tags

    def optimize_prompt(
        self, agent_config, dataset, metric_config, task_config, n_samples, num_threads
    ):
        self._opik_client = opik.Opik()
        self.task_config = task_config
        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric_config.metric.name,
                metadata={"optimizer": self.agent_class.__name__},
            )
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            optimization = None

        if not optimization:
            logger.warning("Continuing without Opik optimization tracking.")

        # Build user's agent invoke method:
        agent = self.agent_class(self, agent_config)

        dataset_item_ids = [
            item["id"] for item in random.sample(dataset.get_items(), n_samples)
        ]

        prompt = [
            agent_config[key]
            for key in agent_config
            if agent_config[key]["type"] == "prompt"
        ][0]

        experiment_config = {
            "optimizer": self.agent_class.__name__,
            "tools": (
                [f["name"] for f in self.task_config.tools]
                if self.task_config.tools
                else []
            ),
            "metric": metric_config.metric.name,
            "dataset": dataset.name,
            "configuration": {
                "prompt": prompt["value"],
            },
            "evaluation": "initial",
        }
        print("Initial prompt:")
        print(prompt["value"])
        score = task_evaluator.evaluate(
            dataset=dataset,
            evaluated_task=agent.invoke,
            metric_config=metric_config,
            dataset_item_ids=dataset_item_ids,
            project_name=self.project_name,
            num_threads=num_threads,
            experiment_config=experiment_config,
            optimization_id=optimization.id,
        )

        experiment_config["evaluation"] = "full"
        # FIXME: the following is a sample optimization
        # and contains some hardcoded bits related
        # to development code. Next step: abstract
        # these out:
        count = 0
        while count < 3:
            new_prompt = agent.llm_invoke(
                """Refine this prompt template to make it better. Just give me the better prompt, nothing else. 

The new prompt must contain {tools}, {agent_scratchpad}, {input}, and [{tool_names}]

Suggest things like keeping the answer brief. The answers should be like answers
to a trivia question.

Here is the prompt: 

%r
"""
                % prompt["value"]
            )
            new_prompt = new_prompt.replace("\\n", "\n")
            experiment_config["configuration"]["prompt"] = new_prompt

            print(new_prompt)

            prompt["value"] = new_prompt

            agent = self.agent_class(self, agent_config)

            count += 1
            score = task_evaluator.evaluate(
                dataset=dataset,
                evaluated_task=agent.invoke,
                metric_config=metric_config,
                dataset_item_ids=dataset_item_ids,
                project_name=self.project_name,
                num_threads=num_threads,
                experiment_config=experiment_config,
                optimization_id=optimization.id,
            )

        if optimization:
            self.update_optimization(optimization, status="completed")
