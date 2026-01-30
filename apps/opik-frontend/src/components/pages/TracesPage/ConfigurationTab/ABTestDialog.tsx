import React, { useState, useEffect } from "react";
import { Split } from "lucide-react";
import { v4 as uuidv4 } from "uuid";

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

const CONFIG_BACKEND_URL = "http://localhost:5050";

type ABTestDialogProps = {
  open: boolean;
  onClose: () => void;
  selectedVariables: ConfigVariable[];
  onSuccess: (testId: string) => void;
  projectId?: string;
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
  projectId = "default",
}) => {
  const [name, setName] = useState("");
  const [values, setValues] = useState<VariableValue[]>([]);
  const [samplingPercent, setSamplingPercent] = useState(50);
  const [isCreating, setIsCreating] = useState(false);

  useEffect(() => {
    if (open) {
      setName("");
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

  const parseValue = (variable: VariableValue): string | number | boolean => {
    if (variable.type === "boolean") {
      return variable.value === "true";
    }
    if (variable.type === "number") {
      return parseFloat(variable.value);
    }
    return variable.value;
  };

  const handleSubmit = async () => {
    setIsCreating(true);

    try {
      const testId = `ab-${uuidv4().slice(0, 8)}`;
      const experimentName = name.trim() || undefined;
      const distribution = {
        A: samplingPercent,
        B: 100 - samplingPercent,
      };

      // Create the A/B test mask
      const maskRes = await fetch(`${CONFIG_BACKEND_URL}/v1/config/masks`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          project_id: projectId,
          env: "prod",
          mask_id: testId,
          name: experimentName,
          is_ab: true,
          distribution,
        }),
      });

      if (!maskRes.ok) {
        throw new Error("Failed to create A/B test");
      }

      // Set variant A overrides (new values)
      for (const variable of values) {
        const res = await fetch(`${CONFIG_BACKEND_URL}/v1/config/masks/override`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            project_id: projectId,
            env: "prod",
            mask_id: testId,
            variant: "A",
            key: variable.key,
            value: parseValue(variable),
          }),
        });

        if (!res.ok) {
          throw new Error(`Failed to set variant A override for ${variable.key}`);
        }
      }

      // Set variant B overrides (control - current production values)
      for (const variable of values) {
        const original = selectedVariables.find((v) => v.key === variable.key);
        if (original) {
          const res = await fetch(`${CONFIG_BACKEND_URL}/v1/config/masks/override`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
              project_id: projectId,
              env: "prod",
              mask_id: testId,
              variant: "B",
              key: variable.key,
              value: original.currentValue,
            }),
          });

          if (!res.ok) {
            throw new Error(`Failed to set variant B override for ${variable.key}`);
          }
        }
      }

      onSuccess(testId);
      onClose();
    } catch (error) {
      console.error("Failed to create A/B test:", error);
    } finally {
      setIsCreating(false);
    }
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

          <div>
            <Label htmlFor="ab-test-name">Name (optional)</Label>
            <Input
              id="ab-test-name"
              className="mt-1.5"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g., happy-falcon-4821 (auto-generated if empty)"
            />
          </div>

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
                <div className="text-xs text-muted-slate">Variant A (new values)</div>
              </div>
              <div className="flex-1 rounded border bg-background p-2 text-center">
                <div className="text-lg font-semibold text-slate-500">
                  {100 - samplingPercent}%
                </div>
                <div className="text-xs text-muted-slate">Variant B (control)</div>
              </div>
            </div>
          </div>

          <Separator />

          <div>
            <Label className="mb-3 block">Variant A Values</Label>
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
                      Variant B (control):{" "}
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
