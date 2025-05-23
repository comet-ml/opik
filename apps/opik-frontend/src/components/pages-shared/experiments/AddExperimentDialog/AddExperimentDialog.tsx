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

export enum EVALUATOR_MODEL {
  equals = "equals",
  contains = "contains",
  regex_match = "regex_match",
  isJSON = "isJson",
  levenshtein = "levenshtein",
  hallucination = "hallucination",
  moderation = "moderation",
  answer_relevance = "answer_relevance",
  context_recall = "context_recall",
  context_precision = "context_precision",
}

export interface ModelData {
  class: string;
  initParameters?: string;
  scoreParameters?: string[];
}

const EVALUATOR_MODEL_MAP: Record<EVALUATOR_MODEL, ModelData> = {
  [EVALUATOR_MODEL.equals]: {
    class: "Equals",
    scoreParameters: ["output", "reference"],
  },
  [EVALUATOR_MODEL.regex_match]: {
    class: "RegexMatch",
    // eslint-disable-next-line no-useless-escape
    initParameters: `regex="\d{3}-\d{2}-\d{4}"`,
    scoreParameters: ["output"],
  },
  [EVALUATOR_MODEL.contains]: {
    class: "Contains",
    scoreParameters: ["output", "reference"],
  },
  [EVALUATOR_MODEL.isJSON]: {
    class: "IsJson",
    scoreParameters: ["output"],
  },
  [EVALUATOR_MODEL.levenshtein]: {
    class: "LevenshteinRatio",
    scoreParameters: ["output", "reference"],
  },
  [EVALUATOR_MODEL.moderation]: {
    class: "Moderation",
    scoreParameters: ["input", "output", "context"],
  },
  [EVALUATOR_MODEL.answer_relevance]: {
    class: "AnswerRelevance",
    scoreParameters: ["input", "output", "context"],
  },
  [EVALUATOR_MODEL.hallucination]: {
    class: "Hallucination",
    scoreParameters: ["input", "output", "context"],
  },
  [EVALUATOR_MODEL.context_recall]: {
    class: "ContextRecall",
    scoreParameters: ["input", "output", "context"],
  },
  [EVALUATOR_MODEL.context_precision]: {
    class: "ContextPrecision",
    scoreParameters: ["input", "output", "context"],
  },
};

const HEURISTICS_MODELS_OPTIONS: DropdownOption<EVALUATOR_MODEL>[] = [
  {
    value: EVALUATOR_MODEL.equals,
    label: "Equals",
    description: "Checks for exact text match.",
  },
  {
    value: EVALUATOR_MODEL.regex_match,
    label: "Regex match",
    description: "Verifies pattern conformity using regex.",
  },
  {
    value: EVALUATOR_MODEL.contains,
    label: "Contains",
    description: "Identifies presence of a substring.",
  },
  {
    value: EVALUATOR_MODEL.isJSON,
    label: "isJson",
    description: "Validates JSON format compliance.",
  },
  {
    value: EVALUATOR_MODEL.levenshtein,
    label: "Levenshtein",
    description: "Calculates text similarity via edit distance.",
  },
];

const LLM_JUDGES_MODELS_OPTIONS: DropdownOption<EVALUATOR_MODEL>[] = [
  {
    value: EVALUATOR_MODEL.hallucination,
    label: "Hallucination",
    description: "Detects generated false information.",
  },
  {
    value: EVALUATOR_MODEL.moderation,
    label: "Moderation",
    description: "Checks adherence to content standards.",
  },
  {
    value: EVALUATOR_MODEL.answer_relevance,
    label: "Answer relevance",
    description: "Evaluates how well the answer fits the question.",
  },
  {
    value: EVALUATOR_MODEL.context_recall,
    label: "Context recall",
    description: "Measures retrieval of relevant context.",
  },
  {
    value: EVALUATOR_MODEL.context_precision,
    label: "Context precision",
    description: "Checks accuracy of provided context details.",
  },
];

const DEFAULT_LOADED_DATASET_ITEMS = 25;

type AddExperimentDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddExperimentDialog: React.FunctionComponent<
  AddExperimentDialogProps
> = ({ open, setOpen }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const [datasetName, setDatasetName] = useState("");
  const [models, setModels] = useState<EVALUATOR_MODEL[]>([
    LLM_JUDGES_MODELS_OPTIONS[0].value,
  ]); // Set the first LLM judge model as checked
  const section1 = "pip install opik";

  const importString =
    models.length > 0
      ? `from opik.evaluation.metrics import (${models
          .map((m) => EVALUATOR_MODEL_MAP[m].class)
          .join(", ")})
  `
      : ``;

  const metricsString =
    models.length > 0
      ? `\nmetrics = [${models
          .map(
            (m) =>
              EVALUATOR_MODEL_MAP[m].class +
              "(" +
              (EVALUATOR_MODEL_MAP[m].initParameters || "") +
              ")",
          )
          .join(", ")}]\n`
      : "";

  const evaluation_task_output =
    models.length > 0
      ? `{
        ${[
          ...new Set(
            models.flatMap((m) => EVALUATOR_MODEL_MAP[m].scoreParameters),
          ),
        ]
          .map((p) =>
            p === "context"
              ? `"${p}": ["placeholder string"]`
              : `"${p}": "placeholder string"`,
          )
          .join(",\n        ")}
    }`
      : `{"output": "placeholder string"}`;

  const metricsParam =
    models.length > 0
      ? `,
  scoring_metrics=metrics`
      : "";

  const section3 =
    "" +
    `import os
from opik import Opik
from opik.evaluation import evaluate

# INJECT_OPIK_CONFIGURATION

${importString}
client = Opik()
dataset = client.get_dataset(name="${
      datasetName || "dataset name placeholder"
    }")

def evaluation_task(dataset_item):
    # your LLM application is called here

    result = ${evaluation_task_output}

    return result
${metricsString}
eval_results = evaluate(
  experiment_name="my_evaluation",
  dataset=dataset,
  task=evaluation_task${metricsParam}
)`;

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

  const checkboxChangeHandler = (id: EVALUATOR_MODEL) => {
    setModels((state) => {
      const localModels = state.slice();
      const index = localModels.indexOf(id);

      if (index !== -1) {
        localModels.splice(index, 1);
      } else {
        localModels.push(id);
      }

      return localModels;
    });
  };

  const generateList = (
    title: string,
    list: DropdownOption<EVALUATOR_MODEL>[],
  ) => {
    return (
      <div>
        <div className="comet-body-s-accented pb-1 pt-2 text-muted-slate">
          {title}
        </div>
        {list.map((m) => {
          return (
            <label key={m.value} className="flex cursor-pointer py-2.5">
              <Checkbox
                checked={models.includes(m.value)}
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
          <SheetTitle>Create a new experiment</SheetTitle>
          <div className="comet-body-s m-auto mt-4 w-[468px] self-center text-center text-muted-slate">
            Select a dataset, assign the relevant evaluators, and follow the
            instructions to track and compare your training runs
          </div>
        </div>
        <div className="m-auto flex w-full max-w-[1250px] items-start gap-6">
          <div className="flex w-[250px] shrink-0 flex-col gap-2">
            <div className="comet-title-s">Select evaluators</div>
            {generateList("Heuristics metrics", HEURISTICS_MODELS_OPTIONS)}
            {generateList("LLM Judges", LLM_JUDGES_MODELS_OPTIONS)}
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
              3. Create an Experiment
            </div>
            <ConfiguredCodeHighlighter code={section3} />
          </div>

          <div className="flex w-[250px] shrink-0 flex-col gap-6 self-start">
            <ApiKeyCard />
            <GoogleColabCard link="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/quickstart_notebook.ipynb" />
          </div>
        </div>
      </div>
    </SideDialog>
  );
};

export default AddExperimentDialog;
