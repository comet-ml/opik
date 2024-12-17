import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { Info, RotateCcw } from "lucide-react";
import { Input } from "@/components/ui/input";
import toNumber from "lodash/toNumber";
import { Slider } from "@/components/ui/slider";
import React, { useEffect, useState } from "react";
import TooltipWrapper, {
  TooltipWrapperProps,
} from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface SliderInputControlProps {
  min: number;
  max: number;
  step: number;
  defaultValue: number;
  value: number;
  onChange: (v: number) => void;
  id: string;
  label: string;
  tooltip: TooltipWrapperProps["content"];
}

const SliderInputControl = ({
  min,
  max,
  step,
  defaultValue,
  value,
  onChange,
  id,
  label,
  tooltip,
}: SliderInputControlProps) => {
  const sliderId = `${id}-slider`;
  const inputId = `${id}-input`;

  const [localValue, setLocalValue] = useState(value.toString());

  const numLocalValue = toNumber(localValue);

  useEffect(() => {
    setLocalValue(value.toString());
  }, [value]);

  const validateAndHandleChange = (value: string) => {
    const numVal = toNumber(value);

    if (value === "") {
      onChange(defaultValue);
      setLocalValue(defaultValue.toString());
      return;
    }

    if (isNaN(numVal)) {
      onChange(defaultValue);
      setLocalValue(defaultValue.toString());
      return;
    }

    if (numVal < min) {
      onChange(min);
      setLocalValue(min.toString());
      return;
    }

    if (numVal > max) {
      onChange(max);
      setLocalValue(max.toString());
      return;
    }

    onChange(numVal);
    setLocalValue(value);
  };

  const handleResetValue = () => {
    setLocalValue(defaultValue.toString());
  };

  return (
    <div className="min-w-60">
      <div className="mb-2 flex w-full items-center justify-between">
        <div className="flex items-center">
          <Label htmlFor={sliderId} className="text-foreground">
            {label}
          </Label>
          <TooltipWrapper content={tooltip}>
            <Info className="ml-1 size-4 text-light-slate" />
          </TooltipWrapper>
        </div>

        <div className="flex items-center">
          {numLocalValue !== defaultValue && (
            <Button variant="minimal" size="icon-sm" onClick={handleResetValue}>
              <RotateCcw className="size-3.5" />
            </Button>
          )}
          <Input
            id={inputId}
            className="box-content w-[var(--input-width)] max-w-[5ch] border px-2 py-0 text-right [&:not(:focus)]:border-transparent"
            style={
              {
                "--input-width": `${localValue?.length}ch`,
              } as React.CSSProperties
            }
            onChange={(event) => setLocalValue(event.target.value)}
            onBlur={(event) => validateAndHandleChange(event.target.value)}
            value={localValue || ""}
            dimension="sm"
            variant="ghost"
          />
        </div>
      </div>
      <Slider
        id={sliderId}
        value={[numLocalValue]}
        onValueChange={(values) => {
          setLocalValue(values[0].toString());
        }}
        onValueCommit={(values) => {
          onChange(Number(values[0]));
        }}
        min={min}
        max={max}
        step={step}
      />
    </div>
  );
};

export default SliderInputControl;
