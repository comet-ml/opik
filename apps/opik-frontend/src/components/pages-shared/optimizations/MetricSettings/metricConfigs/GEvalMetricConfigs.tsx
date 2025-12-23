import React from "react";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { GEvalMetricParameters } from "@/types/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

interface GEvalMetricConfigsProps {
  configs: Partial<GEvalMetricParameters>;
  onChange: (configs: Partial<GEvalMetricParameters>) => void;
}

const GEvalMetricConfigs = ({ configs, onChange }: GEvalMetricConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-2">
        <div className="flex items-center">
          <Label htmlFor="task_introduction" className="mr-1.5 text-sm">
            Task introduction
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.geval_task_introduction]}
          />
        </div>
        <Textarea
          id="task_introduction"
          value={configs.task_introduction ?? ""}
          onChange={(e) =>
            onChange({ ...configs, task_introduction: e.target.value })
          }
          placeholder="Describe the task context and what you're evaluating..."
          className="min-h-16 text-xs"
          rows={3}
        />
      </div>

      <div className="space-y-2">
        <div className="flex items-center">
          <Label htmlFor="evaluation_criteria" className="mr-1.5 text-sm">
            Evaluation criteria
          </Label>
          <ExplainerIcon
            {...EXPLAINERS_MAP[EXPLAINER_ID.geval_evaluation_criteria]}
          />
        </div>
        <Textarea
          id="evaluation_criteria"
          value={configs.evaluation_criteria ?? ""}
          onChange={(e) =>
            onChange({ ...configs, evaluation_criteria: e.target.value })
          }
          placeholder="Define evaluation criteria: accuracy, completeness, relevance..."
          className="min-h-16 text-xs"
          rows={3}
        />
      </div>
    </div>
  );
};

export default GEvalMetricConfigs;
