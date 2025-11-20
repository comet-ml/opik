import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useAppStore from "@/store/AppStore";
import { DropdownOption } from "@/types/shared";
import { Checkbox } from "@/components/ui/checkbox";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import SideDialog from "@/components/shared/SideDialog/SideDialog";
import { SheetTitle } from "@/components/ui/sheet";
import ApiKeyCard from "@/components/pages-shared/onboarding/ApiKeyCard/ApiKeyCard";
import GoogleColabCard from "@/components/pages-shared/onboarding/GoogleColabCard/GoogleColabCard";
import ConfiguredCodeHighlighter from "@/components/pages-shared/onboarding/ConfiguredCodeHighlighter/ConfiguredCodeHighlighter";

export enum OPTIMIZATION_ALGORITHMS {
  fewShotOptimizer = "FewShotBayesianOptimizer",
  metaPromptOptimizer = "MetaPromptOptimizer",
  evolutionaryOptimizer = "EvolutionaryOptimizer",
  gepaOptimizer = "GepaOptimizer",
}

const OPTIMIZATION_ALGORITHMS_OPTIONS: DropdownOption<OPTIMIZATION_ALGORITHMS>[] =
  [
    {
      value: OPTIMIZATION_ALGORITHMS.metaPromptOptimizer,
      label: "Meta optimizer",
      description: "Optimizes prompts using meta learning.",
    },
    {
      value: OPTIMIZATION_ALGORITHMS.fewShotOptimizer,
      label: "Few-shot Bayesian optimizer",
      description:
        "Optimizes prompts using few-shot learning and Bayesian optimization.",
    },
    {
      value: OPTIMIZATION_ALGORITHMS.evolutionaryOptimizer,
      label: "Evolutionary optimizer",
      description: "Optimizes prompts using evolution.",
    },
    {
      value: OPTIMIZATION_ALGORITHMS.gepaOptimizer,
      label: "GEPA optimizer",
      description: "Applies Genetic-Pareto search with reflection guidance.",
    },
  ];

// Hardcoded code templates for each optimization algorithm
const OPTIMIZATION_CODE_TEMPLATES: Record<OPTIMIZATION_ALGORITHMS, string> = {
  [OPTIMIZATION_ALGORITHMS.metaPromptOptimizer]: `# Configure the SDK
import os
# INJECT_OPIK_CONFIGURATION

import opik
from opik_optimizer import (
    ChatPrompt,
    MetaPromptOptimizer,
)
from opik.evaluation.metrics import LevenshteinRatio

# Define the prompt to optimize
prompt = ChatPrompt(
    system="Answer the question.",
    user="{question}", # This must match dataset field
)

# Get the dataset to evaluate the prompt on
client = opik.Opik()
dataset = client.get_dataset(name="DATASET_NAME_PLACEHOLDER")

# Define the metric to evaluate the prompt on
def levenshtein_ratio(dataset_item, llm_output):
    metric = LevenshteinRatio()
    return metric.score(
        reference=dataset_item["answer"], # This must match dataset field
        output=llm_output,
    )

# Run the optimization
optimizer = MetaPromptOptimizer(
    model="openai/gpt-4o-mini",  # Task model (LiteLLM name)
    reasoning_model="openai/gpt-4o",  # Optional reasoning model
    rounds=3,
    num_prompts_per_round=4,
    n_threads=8,
    enable_context=True,
    temperature=0.0,
    seed=42,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)

result.display()
# Optimizer metadata (prompt, tools, version) is logged automatically.
`,

  [OPTIMIZATION_ALGORITHMS.fewShotOptimizer]: `# Configure the SDK
import os
# INJECT_OPIK_CONFIGURATION

import opik
from opik_optimizer import (
    ChatPrompt,
    FewShotBayesianOptimizer,
)
from opik.evaluation.metrics import LevenshteinRatio

# Define the prompt to optimize
prompt = ChatPrompt(
    system="Answer the question.",
    user="{question}", # This must match dataset field
)

# Get the dataset to evaluate the prompt on
client = opik.Opik()
dataset = client.get_dataset(name="DATASET_NAME_PLACEHOLDER")

# Define the metric to evaluate the prompt on
def levenshtein_ratio(dataset_item, llm_output):
    metric = LevenshteinRatio()
    return metric.score(
        reference=dataset_item["answer"], # This must match dataset field
        output=llm_output,
    )

# Run the optimization
optimizer = FewShotBayesianOptimizer(
    model="openai/gpt-4o",  # LiteLLM name
    min_examples=3,
    max_examples=8,
    n_threads=8,
    seed=42,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_trials=10,
    n_samples=50,
)

result.display()
# Optimizer metadata (prompt, tools, version) is logged automatically.
`,

  [OPTIMIZATION_ALGORITHMS.evolutionaryOptimizer]: `# Configure the SDK
import os
# INJECT_OPIK_CONFIGURATION

import opik
from opik_optimizer import (
    ChatPrompt,
    EvolutionaryOptimizer,
)
from opik.evaluation.metrics import LevenshteinRatio

# Define the prompt to optimize
prompt = ChatPrompt(
    system="Answer the question.",
    user="{question}", # This must match dataset field
)

# Get the dataset to evaluate the prompt on
client = opik.Opik()
dataset = client.get_dataset(name="DATASET_NAME_PLACEHOLDER")

# Define the metric to evaluate the prompt on
def levenshtein_ratio(dataset_item, llm_output):
    metric = LevenshteinRatio()
    return metric.score(
        reference=dataset_item["answer"], # This must match dataset field
        output=llm_output,
    )

# Run the optimization
optimizer = EvolutionaryOptimizer(
    model="openai/gpt-4o",  # LiteLLM name
    population_size=10,
    num_generations=3,
    mutation_rate=0.2,
    crossover_rate=0.8,
    enable_moo=False,
    enable_llm_crossover=True,
    infer_output_style=True,
    n_threads=8,
    seed=42,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=10,
)

result.display()
# Optimizer metadata (prompt, tools, version) is logged automatically.
`,

  [OPTIMIZATION_ALGORITHMS.gepaOptimizer]: `# Configure the SDK
import os
# INJECT_OPIK_CONFIGURATION

import opik
from opik_optimizer import (
    ChatPrompt,
    GepaOptimizer,
)
from opik.evaluation.metrics import LevenshteinRatio

# Define the prompt to optimize
prompt = ChatPrompt(
    system="Answer the question.",
    user="{question}",  # This must match dataset field
)

# Get the dataset to evaluate the prompt on
client = opik.Opik()
dataset = client.get_dataset(name="DATASET_NAME_PLACEHOLDER")

# Define the metric to evaluate the prompt on
def levenshtein_ratio(dataset_item, llm_output):
    metric = LevenshteinRatio()
    return metric.score(
        reference=dataset_item["answer"],  # This must match dataset field
        output=llm_output,
    )

# Run the optimization
optimizer = GepaOptimizer(
    model="openai/gpt-4o-mini",  # Task model (LiteLLM name)
    reflection_model="openai/gpt-4o",  # Reflection model for re-ranking
    temperature=0.0,
    max_tokens=400,
)

result = optimizer.optimize_prompt(
    prompt=prompt,
    dataset=dataset,
    metric=levenshtein_ratio,
    n_samples=12,
    max_metric_calls=60,
    reflection_minibatch_size=5,
    candidate_selection_strategy="pareto",
)

result.display()
# Optimizer metadata (prompt, tools, version) is logged automatically.
`,
};

const DEFAULT_LOADED_DATASET_ITEMS = 25;

type AddOptimizationDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddOptimizationDialog: React.FunctionComponent<
  AddOptimizationDialogProps
> = ({ open, setOpen }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const [datasetName, setDatasetName] = useState("");
  const [selectedModel, setSelectedModel] = useState<OPTIMIZATION_ALGORITHMS>(
    OPTIMIZATION_ALGORITHMS.metaPromptOptimizer,
  );

  const section1 = "pip install opik-optimizer";

  // Get the hardcoded code template for the selected algorithm and inject dynamic values
  const section3 = OPTIMIZATION_CODE_TEMPLATES[selectedModel].replace(
    "DATASET_NAME_PLACEHOLDER",
    datasetName || "your-dataset-name",
  );

  const { data, isLoading } = useDatasetsList(
    {
      workspaceName,
      page: 1,
      size: isLoadedMore ? 10000 : DEFAULT_LOADED_DATASET_ITEMS,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const total = data?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    return (data?.content || []).map((dataset) => ({
      value: dataset.name,
      label: dataset.name,
    }));
  }, [data?.content]);

  const openChangeHandler = useCallback(
    (open: boolean) => {
      setOpen(open);
      if (!open) {
        setDatasetName("");
      }
    },
    [setOpen],
  );

  const checkboxChangeHandler = (id: OPTIMIZATION_ALGORITHMS) => {
    setSelectedModel(id);
  };

  const generateList = (list: DropdownOption<OPTIMIZATION_ALGORITHMS>[]) => {
    return (
      <div>
        {list.map((m) => {
          return (
            <label key={m.value} className="flex cursor-pointer py-2.5">
              <Checkbox
                checked={selectedModel === m.value}
                onCheckedChange={() => checkboxChangeHandler(m.value)}
                aria-label="Select row"
                className="mt-0.5"
              />
              <div className="px-2">
                <div className="comet-body-s-accented truncate">{m.label}</div>
                <div className="comet-body-s mt-0.5 text-light-slate">
                  {m.description}
                </div>
              </div>
            </label>
          );
        })}
      </div>
    );
  };

  return (
    <SideDialog open={open} setOpen={openChangeHandler}>
      <div className="pb-20">
        <div className="pb-8">
          <SheetTitle>Start an optimization run</SheetTitle>
          <div className="comet-body-s m-auto mt-4 w-[468px] self-center text-center text-muted-slate">
            Select a dataset, choose the optimizer you would like to use, and we
            will improve your prompt for you
          </div>
        </div>
        <div className="m-auto flex w-full max-w-[1250px] items-start gap-6">
          <div className="flex w-[250px] shrink-0 flex-col gap-2">
            <div className="comet-title-s">Optimization algorithms</div>
            {generateList(OPTIMIZATION_ALGORITHMS_OPTIONS)}
          </div>
          <div className="flex w-full max-w-[700px] flex-col gap-2 rounded-md border border-border p-6">
            <div className="comet-body-s text-foreground-secondary">
              1. Select dataset
            </div>
            <LoadableSelectBox
              options={options}
              value={datasetName}
              placeholder="Select a dataset"
              onChange={setDatasetName}
              onLoadMore={
                total > DEFAULT_LOADED_DATASET_ITEMS && !isLoadedMore
                  ? loadMoreHandler
                  : undefined
              }
              isLoading={isLoading}
              optionsCount={DEFAULT_LOADED_DATASET_ITEMS}
            />
            <div className="comet-body-s mt-4 text-foreground-secondary">
              2. Install the SDK
            </div>
            <CodeHighlighter data={section1} />
            <div className="comet-body-s mt-4 text-foreground-secondary">
              3. Create an Optimization run
            </div>
            <ConfiguredCodeHighlighter code={section3} />
          </div>

          <div className="flex w-[250px] shrink-0 flex-col gap-6 self-start">
            <ApiKeyCard />
            <GoogleColabCard link="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/optimizer_notebook.ipynb" />
          </div>
        </div>
      </div>
    </SideDialog>
  );
};

export default AddOptimizationDialog;
