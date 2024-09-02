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
  regex_match = "regex_match",
  contains = "contains",
  isJSON = "isJSON",
  levenshtein = "levenshtein",
  perplexity_score = "perplexity_score",
  bleu = "bleu",
  rouge = "rouge",
  factuality = "factuality",
  moderation = "moderation",
  battle = "battle",
  answer_relevance = "answer_relevance",
  context_relevance = "context_relevance",
  hallucination = "hallucination",
  context_recall = "context_recall",
  context_precision = "context_precision",
  context_relevancy = "context_relevancy",
}

export interface ModelData {
  class: string;
  params?: string;
}

const EVALUATOR_MODEL_MAP = {
  [EVALUATOR_MODEL.equals]: { class: "Equals" }, // not implemented in SDK
  [EVALUATOR_MODEL.regex_match]: { class: "RegexMatch" }, // not implemented in SDK
  [EVALUATOR_MODEL.contains]: { class: "Contains" },
  [EVALUATOR_MODEL.isJSON]: { class: "IsJSON" },
  [EVALUATOR_MODEL.levenshtein]: { class: "Levenshtein" }, // not implemented in SDK
  [EVALUATOR_MODEL.perplexity_score]: { class: "PerplexityScore" }, // not implemented in SDK
  [EVALUATOR_MODEL.bleu]: { class: "BLEU" }, // not implemented in SDK
  [EVALUATOR_MODEL.rouge]: { class: "Rouge" }, // not implemented in SDK
  [EVALUATOR_MODEL.factuality]: { class: "Factuality" }, // not implemented in SDK
  [EVALUATOR_MODEL.moderation]: { class: "Moderation" }, // not implemented in SDK
  [EVALUATOR_MODEL.battle]: { class: "Battle" }, // not implemented in SDK
  [EVALUATOR_MODEL.answer_relevance]: { class: "AnswerRelevance" }, // not implemented in SDK
  [EVALUATOR_MODEL.context_relevance]: { class: "ContextRelevance" }, // not implemented in SDK
  [EVALUATOR_MODEL.hallucination]: { class: "Hallucination" },
  [EVALUATOR_MODEL.context_recall]: { class: "ContextRecall" }, // not implemented in SDK
  [EVALUATOR_MODEL.context_precision]: { class: "ContextPrecision" }, // not implemented in SDK
  [EVALUATOR_MODEL.context_relevancy]: { class: "ContextRelevancy" }, // not implemented in SDK
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
  {
    value: EVALUATOR_MODEL.perplexity_score,
    label: "Perplexity score",
    description: "Gauges language model prediction accuracy.",
  },
  {
    value: EVALUATOR_MODEL.bleu,
    label: "BLEU",
    description: "Rates quality of machine translations.",
  },
  {
    value: EVALUATOR_MODEL.rouge,
    label: "Rouge",
    description: "Compares summary overlap with references.",
  },
];

const LLM_JUDGES_MODELS_OPTIONS: DropdownOption<EVALUATOR_MODEL>[] = [
  {
    value: EVALUATOR_MODEL.factuality,
    label: "Factuality",
    description: "Assesses correctness of information.",
  },
  {
    value: EVALUATOR_MODEL.moderation,
    label: "Moderation",
    description: "Checks adherence to content standards.",
  },
  {
    value: EVALUATOR_MODEL.battle,
    label: "Battle",
    description: "Compares quality of two outputs.",
  },
  {
    value: EVALUATOR_MODEL.answer_relevance,
    label: "Answer relevance",
    description: "Evaluates how well the answer fits the question.",
  },
  {
    value: EVALUATOR_MODEL.context_relevance,
    label: "Context relevance",
    description: "Assesses suitability within the context.",
  },
  {
    value: EVALUATOR_MODEL.hallucination,
    label: "Hallucination",
    description: "Detects generated false information.",
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
  {
    value: EVALUATOR_MODEL.context_relevancy,
    label: "Context relevancy",
    description: "Evaluates alignment with the given context.",
  },
];

type NewExperimentButtonProps = {
  dataset?: Dataset;
};

const NewExperimentButton: React.FunctionComponent<
  NewExperimentButtonProps
> = ({ dataset }) => {
  const [models, setModels] = useState<EVALUATOR_MODEL[]>([]);
  const datasetName = dataset?.name ?? "";
  const section1 = "pip install opik";
  const section2 = 'export COMET_API_KEY="Your API key"';

  const importString =
    models.length > 0
      ? `from opik.evaluation.metrics import (${models
          .map((m) => EVALUATOR_MODEL_MAP[m].class)
          .join(", ")})
  `
      : ``;

  const metricsString =
    models.length > 0
      ? `
metrics = [${models.map((m) => EVALUATOR_MODEL_MAP[m].class + "()").join(", ")}]
          `
      : "";

  const metricsParam =
    models.length > 0
      ? `,
  metrics=metrics`
      : "";

  const section3 =
    "" +
    `from opik.evaluation import Dataset, evaluate
${importString}
dataset = Dataset().get(name="${datasetName}")
${metricsString}
eval_results = evaluation(
  experiment_name="my_evaluation",
  task=lambda input, expected_output: your_llm_application(input)${metricsParam}
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
