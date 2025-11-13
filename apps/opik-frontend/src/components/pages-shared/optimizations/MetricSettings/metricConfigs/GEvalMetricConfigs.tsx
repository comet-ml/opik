import React from "react";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { GEvalMetricParameters } from "@/types/optimizations";
import { DEFAULT_G_EVAL_METRIC_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";

interface GEvalMetricConfigsProps {
  configs: Partial<GEvalMetricParameters>;
  onChange: (configs: Partial<GEvalMetricParameters>) => void;
}

const GEvalMetricConfigs = ({ configs, onChange }: GEvalMetricConfigsProps) => {
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-2">
        <div className="flex items-center">
          <Label htmlFor="task_introduction" className="text-sm">
            Task introduction
          </Label>
          <ExplainerIcon description="Provide context about the task being evaluated" />
        </div>
        <Textarea
          id="task_introduction"
          value={
            configs.task_introduction ??
            DEFAULT_G_EVAL_METRIC_CONFIGS.TASK_INTRODUCTION
          }
          onChange={(e) =>
            onChange({ ...configs, task_introduction: e.target.value })
          }
          placeholder="Describe the task context and what you're evaluating..."
          className="text-xs"
          rows={5}
        />
      </div>

      <div className="space-y-2">
        <div className="flex items-center">
          <Label htmlFor="evaluation_criteria" className="text-sm">
            Evaluation criteria
          </Label>
          <ExplainerIcon description="Define specific criteria for evaluating the output quality" />
        </div>
        <Textarea
          id="evaluation_criteria"
          value={
            configs.evaluation_criteria ??
            DEFAULT_G_EVAL_METRIC_CONFIGS.EVALUATION_CRITERIA
          }
          onChange={(e) =>
            onChange({ ...configs, evaluation_criteria: e.target.value })
          }
          placeholder="Define evaluation criteria: accuracy, completeness, relevance..."
          className="text-xs"
          rows={5}
        />
      </div>
    </div>
  );
};

export default GEvalMetricConfigs;
