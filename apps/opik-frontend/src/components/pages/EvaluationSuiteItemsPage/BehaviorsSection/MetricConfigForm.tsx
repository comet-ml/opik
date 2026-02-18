import React from "react";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Checkbox } from "@/components/ui/checkbox";
import {
  MetricType,
  MetricConfig,
  StringMatchConfig,
  ThresholdConfig,
  LLMJudgeConfig,
} from "@/types/evaluation-suites";

interface MetricConfigFormProps {
  metricType: MetricType;
  config: MetricConfig;
  onChange: (config: MetricConfig) => void;
}

function renderStringMatchFields(
  config: StringMatchConfig,
  onChange: (config: MetricConfig) => void,
  options: { label: string; placeholder: string; checkboxId: string },
): React.ReactNode {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-2">
        <Label>{options.label}</Label>
        <Input
          placeholder={options.placeholder}
          value={config.value ?? ""}
          onChange={(e) => onChange({ ...config, value: e.target.value })}
        />
      </div>
      <div className="flex items-center gap-2">
        <Checkbox
          id={options.checkboxId}
          checked={config.case_sensitive ?? false}
          onCheckedChange={(checked) =>
            onChange({ ...config, case_sensitive: !!checked })
          }
        />
        <Label htmlFor={options.checkboxId}>Case sensitive</Label>
      </div>
    </div>
  );
}

const MetricConfigForm: React.FC<MetricConfigFormProps> = ({
  metricType,
  config,
  onChange,
}) => {
  switch (metricType) {
    case MetricType.LLM_AS_JUDGE: {
      const c = config as LLMJudgeConfig;
      return (
        <div className="flex flex-col gap-2">
          <Label>Expected behavior</Label>
          <Textarea
            placeholder="Describe the expected behavior..."
            value={c.assertions?.[0] ?? ""}
            onChange={(e) =>
              onChange({ assertions: [e.target.value] } as LLMJudgeConfig)
            }
            rows={3}
          />
        </div>
      );
    }
    case MetricType.CONTAINS:
      return renderStringMatchFields(
        config as StringMatchConfig,
        onChange,
        {
          label: "Value",
          placeholder: "Text that output must contain",
          checkboxId: "case-sensitive-contains",
        },
      );
    case MetricType.EQUALS:
      return renderStringMatchFields(
        config as StringMatchConfig,
        onChange,
        {
          label: "Expected value",
          placeholder: "Exact expected value",
          checkboxId: "case-sensitive-equals",
        },
      );
    case MetricType.LEVENSHTEIN_RATIO:
    case MetricType.HALLUCINATION:
    case MetricType.MODERATION: {
      const c = config as ThresholdConfig;
      return (
        <div className="flex flex-col gap-2">
          <Label>Pass threshold (0-1)</Label>
          <Input
            type="number"
            min={0}
            max={1}
            step={0.1}
            placeholder="0.8"
            value={c.threshold ?? ""}
            onChange={(e) =>
              onChange({ threshold: parseFloat(e.target.value) || 0 })
            }
          />
          <p className="comet-body-xs text-muted-slate">
            Score &ge; threshold means pass
          </p>
        </div>
      );
    }
    default:
      return null;
  }
};

export default MetricConfigForm;
