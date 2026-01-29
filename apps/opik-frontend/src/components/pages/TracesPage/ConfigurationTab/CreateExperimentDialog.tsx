import React, { useState, useEffect } from "react";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogFooter,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tag } from "@/components/ui/tag";
import { Separator } from "@/components/ui/separator";
import { ConfigVariable } from "@/api/config/useConfigVariables";
import useCreateExperiment from "@/api/config/useCreateExperiment";

type CreateExperimentDialogProps = {
  open: boolean;
  onClose: () => void;
  selectedVariables: ConfigVariable[];
  onSuccess: (experimentId: string) => void;
};

type VariableValue = {
  key: string;
  value: string;
  type: ConfigVariable["type"];
};

const CreateExperimentDialog: React.FC<CreateExperimentDialogProps> = ({
  open,
  onClose,
  selectedVariables,
  onSuccess,
}) => {
  const [values, setValues] = useState<VariableValue[]>([]);
  const createMutation = useCreateExperiment();

  useEffect(() => {
    if (open) {
      setValues(
        selectedVariables.map((v) => ({
          key: v.key,
          value: String(v.currentValue),
          type: v.type,
        })),
      );
    }
  }, [open, selectedVariables]);

  const handleValueChange = (key: string, newValue: string) => {
    setValues((prev) =>
      prev.map((v) => (v.key === key ? { ...v, value: newValue } : v)),
    );
  };

  const handleSubmit = () => {
    const variables = values.map((v) => {
      let value: string | number | boolean = v.value;
      if (v.type === "boolean") {
        value = v.value === "true";
      } else if (v.type === "number") {
        value = parseFloat(v.value);
      }
      return { key: v.key, value };
    });

    createMutation.mutate(
      { variables },
      {
        onSuccess: (result) => {
          onSuccess(result.experimentId);
          onClose();
        },
      },
    );
  };

  const renderValueEditor = (variable: VariableValue) => {
    if (variable.type === "boolean") {
      return (
        <div className="flex gap-2">
          <Button
            variant={variable.value === "true" ? "default" : "outline"}
            size="sm"
            onClick={() => handleValueChange(variable.key, "true")}
          >
            True
          </Button>
          <Button
            variant={variable.value === "false" ? "default" : "outline"}
            size="sm"
            onClick={() => handleValueChange(variable.key, "false")}
          >
            False
          </Button>
        </div>
      );
    }

    if (variable.type === "number") {
      return (
        <Input
          type="number"
          value={variable.value}
          onChange={(e) => handleValueChange(variable.key, e.target.value)}
          step={variable.key === "temperature" ? 0.1 : 1}
        />
      );
    }

    return (
      <Input
        value={variable.value}
        onChange={(e) => handleValueChange(variable.key, e.target.value)}
      />
    );
  };

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Create Experiment</DialogTitle>
        </DialogHeader>

        <div className="space-y-4 py-4">
          <p className="text-sm text-muted-slate">
            Set override values for the selected configuration variables. These
            values will only be used when the agent is called with the
            experiment ID.
          </p>

          <Separator />

          <div className="max-h-[400px] space-y-4 overflow-y-auto">
            {values.map((variable) => {
              const original = selectedVariables.find(
                (v) => v.key === variable.key,
              );
              return (
                <div key={variable.key} className="space-y-2">
                  <div className="flex items-center gap-2">
                    <Label className="font-mono">{variable.key}</Label>
                    <Tag variant="gray" size="sm" className="capitalize">
                      {variable.type}
                    </Tag>
                  </div>
                  {renderValueEditor(variable)}
                  <p className="text-xs text-muted-slate">
                    Current production value:{" "}
                    <span className="font-mono">
                      {String(original?.currentValue)}
                    </span>
                  </p>
                </div>
              );
            })}
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={createMutation.isPending || values.length === 0}
          >
            {createMutation.isPending ? "Creating..." : "Create Experiment"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default CreateExperimentDialog;
