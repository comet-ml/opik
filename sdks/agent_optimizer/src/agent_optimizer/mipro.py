"""
Wrapper around dspy's MIPROv2 for Opik
"""

import uuid

import opik
from opik.integrations.dspy.callback import OpikCallback
from opik.optimizer import BaseOptimizer
from opik.evaluation.metrics import BaseMetric

import dspy
from dspy.datasets.dataset import Dataset
from dspy.clients.base_lm import BaseLM
from tqdm import tqdm

from .mipro_optimizer_v2 import MIPROv2
from .utils import State


def create_dspy_signature(
    input: str,
    output: str,
    prompt: str = None,
):
    """
    Create a dspy Signature given inputs, outputs, prompt
    """
    attributes = {
        "__doc__": prompt,
        "__annotations__": {},
    }
    attributes["__annotations__"][input] = str
    attributes[input] = dspy.InputField(desc="")
    attributes["__annotations__"][output] = str
    attributes[output] = dspy.OutputField(desc="")
    return type("MySignature", (dspy.Signature,), attributes)


def opik_metric_to_dspy(metric, output):
    answer_field = output

    def opik_metric_score_wrapper(example, prediction, trace=None):
        result = getattr(metric, "score")(
            output=getattr(prediction, answer_field),
            reference=getattr(example, answer_field),
        )
        return result.value

    return opik_metric_score_wrapper


def create_dspy_training_set(data: list[dict], input: str) -> list[dspy.Example]:
    """
    Turn a list of dicts into a list of dspy Examples
    """
    output = []
    for example in data:
        example_obj = dspy.Example(
            **example, dspy_uuid=str(uuid.uuid4()), dspy_split="train"
        )
        example_obj = example_obj.with_inputs(input)
        output.append(example_obj)
    return output


class MiproOptimizer(BaseOptimizer):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)

        self.strategy = "Predict"
        self.tools = []
        self.num_threads = 6
        self.model_kwargs["model"] = self.model
        lm = dspy.LM(**self.model_kwargs)
        dspy.configure(lm=lm)  # , callbacks=[opik_callback])

    def optimize_prompt(
        self,
        dataset: str | Dataset,
        metric: BaseMetric,
        prompt: str,
        input: str,
        output: str,
    ):
        self.num_candidates = 10
        self.seed = 9
        self.input = input
        self.output = output
        self.prompt = prompt

        # Convert to values for MIPRO:
        if isinstance(dataset, str):
            opik_client = opik.Opik(project_name=self.project_name)
            self.dataset = opik_client.get_dataset(dataset).get_items()
        else:
            self.dataset = dataset

        # Validate dataset:
        for row in self.dataset:
            if self.input not in row:
                raise Exception("row does not contain input field: %r" % self.input)
            if self.output not in row:
                raise Exception("row does not contain output field: %r" % self.output)

        self.trainset = create_dspy_training_set(self.dataset, self.input)
        self.data_signature = create_dspy_signature(
            self.input, self.output, self.prompt
        )

        if self.tools:
            if self.strategy not in ["", None, "ReAct"]:
                print("Cannot use tools with %r; using 'ReAct'" % self.strategy)
            self.module = dspy.ReAct(self.data_signature, tools=self.tools)
        else:
            self.module = getattr(dspy, self.strategy)(self.data_signature)

        self.metric_function = opik_metric_to_dspy(metric, self.output)

        # Initialize the optimizer:
        self.optimizer = MIPROv2(
            metric=self.metric_function,
            auto="light",
            num_threads=self.num_threads,
            verbose=False,
            num_candidates=self.num_candidates,
            seed=self.seed,
        )

        # Get the initial state:
        state = self.optimizer.step_0(
            student=self.module,
            trainset=self.trainset,
            provide_traceback=True,
        )
        # Bootstrap the few-shot examples:
        self.optimizer.step_1(state)
        print("Demonstration candidates:")
        for key, value in state.demo_candidates.items():
            print("    ", "Group:", key + 1, "number of examples:", len(value[1]))
        # Propose instruction candidates:
        self.optimizer.step_2(state)
        print("Instruction candidates:")
        for key, value in state.instruction_candidates.items():
            print("    Group:", key + 1)
            for i, instruction in enumerate(value):
                print("        ", i + 1, ":", instruction)
        # # Step 3: Find optimal prompt parameters
        self.results = self.optimizer.step_3(state)
        self.best_programs = sorted(
            self.results.candidate_programs,
            key=lambda item: item["score"],
            reverse=True,
        )
        self.state = state
        return self.results

    def display_best_prompts(self):
        print("Best prompts:")
        for x in self.best_programs:
            print(x["score"], x["program"].signature.instructions)

    def get_best_prompt(self, from_top=0):
        return self.best_programs[from_top]["program"].signature.instructions

    def evaluate_prompt_on_dataset(
        self, dataset, metric, prompt, count=None, verbose=True
    ):
        if isinstance(dataset, str):
            opik_client = opik.Opik(project_name=self.project_name)
            self.dataset = opik_client.get_dataset(dataset).get_items()
        else:
            self.dataset = dataset

        # FIXME: Pass in input/output again?
        metric_function = opik_metric_to_dspy(metric, self.output)

        if count is None:
            count = len(self.dataset)
        print(
            "Evaluating prompt on dataset... (count is %s, metric is %r)"
            % (count, metric)
        )
        print("Prompt: %r" % prompt)
        total_score = 0
        signature = create_dspy_signature(self.input, self.output, prompt)
        program = dspy.Predict(signature)
        lm = dspy.settings.lm
        for row in tqdm(self.dataset[:count]):
            output = program(**{self.input: row[self.input]})
            if verbose:
                print("-" * 50)
                print("Input:", self.input)
                print("Assistant:", getattr(output, self.output))
                print("Actual:", self.row[self.output])
            score = metric_function(
                State({self.output: getattr(output, self.output)}),
                State({self.output: row[self.output]}),
            )
            if verbose:
                print("Score:", score)
            total_score += score
        print("=" * 50)
        print("Total score:", total_score)
        print()