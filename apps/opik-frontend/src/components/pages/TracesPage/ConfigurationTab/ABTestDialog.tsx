import React, { useState, useEffect } from "react";
import { Split } from "lucide-react";

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
import { Slider } from "@/components/ui/slider";
import { ConfigVariable } from "@/api/config/useConfigVariables";

type ABTestDialogProps = {
  open: boolean;
  onClose: () => void;
  selectedVariables: ConfigVariable[];
  onSuccess: (testId: string) => void;
};

type VariableValue = {
  key: string;
  value: string;
  type: ConfigVariable["type"];
};

const ABTestDialog: React.FC<ABTestDialogProps> = ({
  open,
  onClose,
  selectedVariables,
  onSuccess,
}) => {
  const [values, setValues] = useState<VariableValue[]>([]);
  const [samplingPercent, setSamplingPercent] = useState(50);
  const [isCreating, setIsCreating] = useState(false);

  useEffect(() => {
    if (open) {
      setValues(
        selectedVariables.map((v) => ({
          key: v.key,
          value: String(v.currentValue),
          type: v.type,
        })),
      );
      setSamplingPercent(50);
    }
  }, [open, selectedVariables]);

  const handleValueChange = (key: string, newValue: string) => {
    setValues((prev) =>
      prev.map((v) => (v.key === key ? { ...v, value: newValue } : v)),
    );
  };

  const handleSubmit = () => {
    setIsCreating(true);

    // Mock creating an A/B test
    setTimeout(() => {
      const testId = `ab-${Date.now().toString(36)}`;
      setIsCreating(false);
      onSuccess(testId);
      onClose();
    }, 1000);
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
          <div className="flex items-center gap-2">
            <div className="flex size-8 items-center justify-center rounded-lg bg-gradient-to-br from-blue-500 to-cyan-500">
              <Split className="size-4 text-white" />
            </div>
            <DialogTitle>Create A/B Test</DialogTitle>
          </div>
        </DialogHeader>

        <div className="space-y-4 py-4">
          <p className="text-sm text-muted-slate">
            Run an A/B test to compare variant values against production.
            Traffic will be automatically split based on your sampling percentage.
          </p>

          <Separator />

          <div className="rounded-lg border bg-muted/30 p-4">
            <div className="mb-4 flex items-center justify-between">
              <Label>Traffic Split</Label>
              <span className="font-mono text-sm font-medium">
                {samplingPercent}% variant / {100 - samplingPercent}% control
              </span>
            </div>
            <Slider
              value={[samplingPercent]}
              onValueChange={([value]) => setSamplingPercent(value)}
              min={1}
              max={99}
              step={1}
              className="w-full"
            />
            <div className="mt-3 flex justify-between text-xs text-muted-slate">
              <span>More control</span>
              <span>More variant</span>
            </div>
            <div className="mt-4 flex gap-2">
              <div className="flex-1 rounded border bg-background p-2 text-center">
                <div className="text-lg font-semibold text-blue-500">
                  {samplingPercent}%
                </div>
                <div className="text-xs text-muted-slate">Variant (new values)</div>
              </div>
              <div className="flex-1 rounded border bg-background p-2 text-center">
                <div className="text-lg font-semibold text-slate-500">
                  {100 - samplingPercent}%
                </div>
                <div className="text-xs text-muted-slate">Control (production)</div>
              </div>
            </div>
          </div>

          <Separator />

          <div>
            <Label className="mb-3 block">Variant Values</Label>
            <div className="max-h-[250px] space-y-4 overflow-y-auto">
              {values.map((variable) => {
                const original = selectedVariables.find(
                  (v) => v.key === variable.key,
                );
                return (
                  <div key={variable.key} className="space-y-2">
                    <div className="flex items-center gap-2">
                      <Label className="font-mono text-sm">{variable.key}</Label>
                      <Tag variant="gray" size="sm" className="capitalize">
                        {variable.type}
                      </Tag>
                    </div>
                    {renderValueEditor(variable)}
                    <p className="text-xs text-muted-slate">
                      Control value:{" "}
                      <span className="font-mono">
                        {String(original?.currentValue)}
                      </span>
                    </p>
                  </div>
                );
              })}
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={isCreating || values.length === 0}
            className="bg-gradient-to-r from-blue-500 to-cyan-500 hover:from-blue-600 hover:to-cyan-600"
          >
            {isCreating ? "Creating..." : "Start A/B Test"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ABTestDialog;
