from typing import Any, Callable, Dict, List, Literal, Optional, Tuple
import os

import random
import logging

import litellm
from litellm.integrations.opik.opik import OpikLogger

import opik
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer import (
    task_evaluator,
)

logger = logging.getLogger(__name__)


class OpikAgent:
    def __init__(self, agent_config, project_name):
        self.initial_agent_config = agent_config
        self.project_name = project_name
        self.init_llm()
        self.reconfig(agent_config)

    def init_llm(self):
        # Litellm bug requires this:
        os.environ["OPIK_PROJECT_NAME"] = self.project_name
        self.opik_logger = OpikLogger()
        litellm.callbacks = [self.opik_logger]

    def llm_invoke(self, prompt):
        response = litellm.completion(
            model="gpt-4o-mini", messages=[{"role": "user", "content": prompt}]
        )
        new_prompt = response.choices[0].message.content
        return new_prompt


class OpikAgentOptimizer(BaseOptimizer):
    def __init__(self, task_config):
        self.task_config = task_config

    def optimize_agent(
        self, agent, dataset, metric_config, n_samples, num_threads, metaprompt
    ):
        self._opik_client = opik.Opik()
        # FIXME: deepcopy?
        agent_config = agent.initial_agent_config
        optimization = None
        try:
            optimization = self._opik_client.create_optimization(
                dataset_name=dataset.name,
                objective_name=metric_config.metric.name,
                metadata={"optimizer": agent.__class__.__name__},
            )
        except Exception:
            logger.warning(
                "Opik server does not support optimizations. Please upgrade opik."
            )
            optimization = None

        if not optimization:
            logger.warning("Continuing without Opik optimization tracking.")

        # Build user's agent invoke method:
        agent.reconfig(agent_config)

        dataset_item_ids = [
            item["id"] for item in random.sample(dataset.get_items(), n_samples)
        ]

        # Reference:
        prompt = [
            agent_config[key]
            for key in agent_config
            if agent_config[key]["type"] == "prompt"
        ][0]

        experiment_config = {
            "optimizer": agent.__class__.__name__,
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
            evaluated_task=lambda dataset_item: agent.invoke(
                dataset_item, self.task_config.input_dataset_fields[0]
            ),
            metric_config=metric_config,
            dataset_item_ids=dataset_item_ids,
            project_name=agent.project_name,
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
            new_prompt = agent.llm_invoke(metaprompt % prompt["value"])
            new_prompt = new_prompt.replace("\\n", "\n")
            experiment_config["configuration"]["prompt"] = new_prompt

            print(new_prompt)

            prompt["value"] = new_prompt

            agent.reconfig(agent_config)

            count += 1
            score = task_evaluator.evaluate(
                dataset=dataset,
                evaluated_task=lambda dataset_item: agent.invoke(
                    dataset_item, self.task_config.input_dataset_fields[0]
                ),
                metric_config=metric_config,
                dataset_item_ids=dataset_item_ids,
                project_name=agent.project_name,
                num_threads=num_threads,
                experiment_config=experiment_config,
                optimization_id=optimization.id,
            )

        if optimization:
            self.update_optimization(optimization, status="completed")
