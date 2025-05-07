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
  miproOptimizer = "MiproOptimizer",
}

export interface ModelData {
  class: string;
  initParameters?: string;
  scoreParameters?: string[];
}

const OPTIMIZATION_ALGORITHMS_MAP: Record<OPTIMIZATION_ALGORITHMS, ModelData> =
  {
    [OPTIMIZATION_ALGORITHMS.metaPromptOptimizer]: {
      class: "MetaPromptOptimizer",
    },
    [OPTIMIZATION_ALGORITHMS.fewShotOptimizer]: {
      class: "FewShotBayesianOptimizer",
    },
    [OPTIMIZATION_ALGORITHMS.miproOptimizer]: {
      class: "MiproOptimizer",
    },
  };

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
      value: OPTIMIZATION_ALGORITHMS.miproOptimizer,
      label: "Mipro optimizer",
      description: "Optimizes prompts using Mipro.",
    },
  ];

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

  let optional_parameters = "";
  if (
    selectedModel === OPTIMIZATION_ALGORITHMS.fewShotOptimizer ||
    selectedModel === OPTIMIZATION_ALGORITHMS.miproOptimizer
  ) {
    optional_parameters = "\n        use_chat_prompt=True,";
  }
  const importString = `from opik_optimizer import ${OPTIMIZATION_ALGORITHMS_MAP[selectedModel].class}

import os
import opik
from opik.evaluation.metrics import Equals
from opik_optimizer import (
    MetricConfig,
    TaskConfig,
    from_dataset_field,
    from_llm_response_text,
)`;

  const section3 =
    "" +
    `${importString}

# Configure the SDK
# INJECT_OPIK_CONFIGURATION

# Define the prompt to optimize
prompt = "Answer the question."

# Get the dataset to evaluate the prompt on
client = opik.Opik()
dataset = client.get_dataset(name="${
      datasetName || "dataset name placeholder"
    }")

# Define the metric to evaluate the prompt on
metric_config = MetricConfig(
    metric=Equals(),
    inputs={
        "output": from_llm_response_text(),
        "reference": from_dataset_field(name="expected_output"),
    },
)

# Run the optimization
optimizer = ${OPTIMIZATION_ALGORITHMS_MAP[selectedModel].class}(
    model="gpt-4o",
)

result = optimizer.optimize_prompt(
    dataset=dataset,
    metric_config=metric_config,
    task_config=TaskConfig(
        instruction_prompt=prompt,
        input_dataset_fields=["input"],
        output_dataset_field="expected_output",${optional_parameters}
    )
)

print(result)
`;

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
          <div className="flex w-full max-w-[700px] flex-col gap-2 rounded-md border border-slate-200 p-6">
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
