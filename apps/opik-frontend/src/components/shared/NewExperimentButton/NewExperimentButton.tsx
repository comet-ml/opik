import React, { useState } from "react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Dataset } from "@/types/datasets";
import { DropdownOption } from "@/types/shared";
import { Checkbox } from "@/components/ui/checkbox";
import CodeHighlighter from "@/components/shared/CodeHighlighter/CodeHighlighter";
import { Info } from "lucide-react";

export enum EVALUATOR_MODEL {
  equals = "equals",
  contains = "contains",
  regex_match = "regex_match",
  isJSON = "isJSON",
  levenshtein = "levenshtein",
  hallucination = "hallucination",
  moderation = "moderation",
  answer_relevance = "answer_relevance",
  context_recall = "context_recall",
  context_precision = "context_precision",
}

export interface ModelData {
  class: string;
  params?: string;
}

const EVALUATOR_MODEL_MAP = {
  [EVALUATOR_MODEL.equals]: {
    class: "Equals",
    init_parameters: "",
    score_parameters: ["output", "reference"],
  },
  [EVALUATOR_MODEL.regex_match]: {
    class: "RegexMatch",
    // eslint-disable-next-line no-useless-escape
    init_parameters: `regex="\d{3}-\d{2}-\d{4}"`,
    score_parameters: ["output"],
  },
  [EVALUATOR_MODEL.contains]: {
    class: "Contains",
    init_parameters: "",
    score_parameters: ["output", "reference"],
  },
  [EVALUATOR_MODEL.isJSON]: {
    class: "IsJSON",
    init_parameters: "",
    score_parameters: ["output"],
  },
  [EVALUATOR_MODEL.levenshtein]: {
    class: "LevenshteinRatio",
    init_parameters: "",
    score_parameters: ["output", "reference"],
  },
  [EVALUATOR_MODEL.moderation]: {
    class: "Moderation",
    init_parameters: "",
    score_parameters: ["input", "output", "context"],
  },
  [EVALUATOR_MODEL.answer_relevance]: {
    class: "AnswerRelevance",
    init_parameters: "",
    score_parameters: ["input", "output", "context"],
  },
  [EVALUATOR_MODEL.hallucination]: {
    class: "Hallucination",
    init_parameters: "",
    score_parameters: ["input", "output", "context"],
  },
  [EVALUATOR_MODEL.context_recall]: {
    class: "ContextRecall",
    init_parameters: "",
    score_parameters: ["input", "output", "context"],
  },
  [EVALUATOR_MODEL.context_precision]: {
    class: "ContextPrecision",
    init_parameters: "",
    score_parameters: ["input", "output", "context"],
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
    label: "isJSON",
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

type NewExperimentButtonProps = {
  dataset?: Dataset;
};

const NewExperimentButton: React.FunctionComponent<
  NewExperimentButtonProps
> = ({ dataset }) => {
  const [models, setModels] = useState<EVALUATOR_MODEL[]>([
    LLM_JUDGES_MODELS_OPTIONS[0].value,
  ]); // Set the first LLM judge model as checked
  const datasetName = dataset?.name ?? "";
  const section1 = "pip install opik";
  const section2 =
    'export OPIK_API_KEY="Your API key"\nexport OPIK_WORKSPACE="Your workspace"';

  const importString =
    models.length > 0
      ? `from opik.evaluation.metrics import (${models
          .map((m) => EVALUATOR_MODEL_MAP[m].class)
          .join(", ")})
  `
      : ``;

  const metricsString =
    models.length > 0
      ? `metrics = [${models
          .map(
            (m) =>
              EVALUATOR_MODEL_MAP[m].class +
              "(" +
              EVALUATOR_MODEL_MAP[m].init_parameters +
              ")",
          )
          .join(", ")}]`
      : "";

  const evaluation_task = `def evaluation_task(dataset_item):
    # your LLM application is called here
    
    result = {
        ${[
          ...new Set(
            models.flatMap((m) => EVALUATOR_MODEL_MAP[m].score_parameters),
          ),
        ]
          .map((p) =>
            p === "context"
              ? `"${p}": ["placeholder string"]`
              : `"${p}": "placeholder string"`,
          )
          .join(",\n        ")}
    }
    return result`;

  const metricsParam =
    models.length > 0
      ? `,
  scoring_metrics=metrics`
      : "";

  const section3 =
    "" +
    `from opik import Opik
from opik.evaluation import evaluate
${importString}
client = Opik()
dataset = client.get_dataset(name="${datasetName}")

${evaluation_task}

${metricsString}

eval_results = evaluate(
  experiment_name="my_evaluation",
  dataset=dataset,
  task=evaluation_task${metricsParam}
)`;

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
        <div className="comet-title-xs">{title}</div>
        {list.map((m) => {
          return (
            <label
              key={m.value}
              className="mb-1 flex cursor-pointer flex-row items-center rounded p-1 hover:bg-muted"
            >
              <Checkbox
                checked={models.includes(m.value)}
                onCheckedChange={() => checkboxChangeHandler(m.value)}
                aria-label="Select row"
              />
              <div className="px-2">
                <div>{m.label}</div>
                <div className="comet-body-xs text-foreground">
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
    <Dialog>
      <DialogTrigger asChild>
        <Button variant="outline">
          <Info className="mr-2 size-4" />
          Create new experiment
        </Button>
      </DialogTrigger>
      <DialogContent className="h-[90vh] w-[90vw]">
        <DialogHeader>
          <DialogTitle>Create a new experiment</DialogTitle>
        </DialogHeader>
        <div className="size-full overflow-y-auto">
          <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,2fr)] gap-6">
            <div className="flex flex-col gap-6">
              <div className="comet-title-s">Select evaluators</div>
              {generateList("Heuristics metrics", HEURISTICS_MODELS_OPTIONS)}
              {generateList("LLM Judges", LLM_JUDGES_MODELS_OPTIONS)}
            </div>
            <div className="flex flex-col gap-6">
              <div className="comet-title-s">1. Install the SDK</div>
              <CodeHighlighter data={section1} />
              <div className="comet-title-s">2. Configure your API key</div>
              <CodeHighlighter data={section2} />
              <div className="comet-title-s">3. Create an Experiment</div>
              <CodeHighlighter data={section3} />
            </div>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default NewExperimentButton;
