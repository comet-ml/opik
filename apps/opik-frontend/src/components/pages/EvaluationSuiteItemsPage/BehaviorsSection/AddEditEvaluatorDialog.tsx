import React, { useState, useEffect } from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
  DialogClose,
  DialogAutoScrollBody,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import MetricConfigForm from "./MetricConfigForm";
import {
  MetricType,
  MetricConfig,
  LLMJudgeConfig,
  METRIC_TYPE_LABELS,
  BehaviorDisplayRow,
  generateBehaviorName,
} from "@/types/evaluation-suites";

interface AddEditBehaviorDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  behavior?: BehaviorDisplayRow;
  onSubmit: (behavior: Omit<BehaviorDisplayRow, "id">) => void;
}

const DEFAULT_CONFIGS: Record<MetricType, MetricConfig> = {
  [MetricType.LLM_AS_JUDGE]: { assertions: [""] },
  [MetricType.CONTAINS]: { value: "", case_sensitive: false },
  [MetricType.EQUALS]: { value: "", case_sensitive: false },
  [MetricType.LEVENSHTEIN_RATIO]: { threshold: 0.8 },
  [MetricType.HALLUCINATION]: { threshold: 0.8 },
  [MetricType.MODERATION]: { threshold: 0.8 },
};

const AddEditBehaviorDialog: React.FC<AddEditBehaviorDialogProps> = ({
  open,
  setOpen,
  behavior,
  onSubmit,
}) => {
  const isEdit = Boolean(behavior);

  const [name, setName] = useState("");
  const [nameManuallySet, setNameManuallySet] = useState(false);
  const [metricType, setMetricType] = useState<MetricType>(
    MetricType.LLM_AS_JUDGE,
  );
  const [config, setConfig] = useState<MetricConfig>(
    DEFAULT_CONFIGS[MetricType.LLM_AS_JUDGE],
  );

  useEffect(() => {
    if (open) {
      if (behavior) {
        setName(behavior.name);
        setNameManuallySet(true);
        setMetricType(behavior.metric_type);
        setConfig(behavior.metric_config);
      } else {
        setName("");
        setNameManuallySet(false);
        setMetricType(MetricType.LLM_AS_JUDGE);
        setConfig(DEFAULT_CONFIGS[MetricType.LLM_AS_JUDGE]);
      }
    }
  }, [open, behavior]);

  useEffect(() => {
    if (!nameManuallySet) {
      setName(generateBehaviorName(metricType, config));
    }
  }, [metricType, config, nameManuallySet]);

  const handleMetricTypeChange = (type: MetricType) => {
    setMetricType(type);
    setConfig(DEFAULT_CONFIGS[type]);
    setNameManuallySet(false);
  };

  const handleNameChange = (value: string) => {
    setName(value);
    setNameManuallySet(value.length > 0);
  };

  const isValid =
    metricType !== MetricType.LLM_AS_JUDGE ||
    (config as LLMJudgeConfig).assertions?.[0]?.trim().length > 0;

  const handleSubmit = () => {
    const finalName =
      name.trim() || generateBehaviorName(metricType, config);
    onSubmit({
      name: finalName,
      metric_type: metricType,
      metric_config: config,
    });
    setOpen(false);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>
            {isEdit ? "Edit behavior" : "Add new behavior"}
          </DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label>Metric type</Label>
              <Select
                value={metricType}
                onValueChange={(v) => handleMetricTypeChange(v as MetricType)}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {Object.entries(METRIC_TYPE_LABELS).map(([key, label]) => (
                    <SelectItem key={key} value={key}>
                      {label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <MetricConfigForm
              metricType={metricType}
              config={config}
              onChange={setConfig}
            />

            <div className="flex flex-col gap-2">
              <Label>
                Name{" "}
                <span className="text-muted-slate">(optional)</span>
              </Label>
              <Input
                placeholder={generateBehaviorName(metricType, config)}
                value={nameManuallySet ? name : ""}
                onChange={(e) => handleNameChange(e.target.value)}
              />
            </div>
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button disabled={!isValid} onClick={handleSubmit}>
            {isEdit ? "Save behavior" : "Add behavior"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditBehaviorDialog;
