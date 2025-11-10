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
  value: number | null | undefined;
  onChange: (v: number) => void;
  id: string;
  label: string;
  tooltip?: TooltipWrapperProps["content"];
  resetDisabled?: boolean;
  suffix?: string;
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
  resetDisabled,
  suffix,
}: SliderInputControlProps) => {
  const sliderId = `${id}-slider`;
  const inputId = `${id}-input`;

  const [localValue, setLocalValue] = useState(
    (value ?? defaultValue).toString(),
  );

  useEffect(() => {
    setLocalValue((value ?? defaultValue).toString());
  }, [value, defaultValue]);

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
    onChange(defaultValue);
    setLocalValue(defaultValue.toString());
  };

  return (
    <div className="min-w-60">
      <div className="mb-2 flex w-full items-center justify-between">
        <div className="flex items-center">
          <Label htmlFor={sliderId} className="text-foreground">
            {label}
          </Label>
          {tooltip && (
            <TooltipWrapper content={tooltip}>
              <Info className="ml-1 size-4 text-light-slate" />
            </TooltipWrapper>
          )}
        </div>

        <div className="flex items-center">
          {!resetDisabled && (value ?? defaultValue) !== defaultValue && (
            <Button variant="minimal" size="icon-sm" onClick={handleResetValue}>
              <RotateCcw />
            </Button>
          )}
          <Input
            id={inputId}
            className="box-content w-[var(--input-width)] max-w-[5ch] border px-2 py-0 text-right [&:not(:focus)]:border-transparent [&:not(:focus)]:px-0.5"
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
            max={max}
          />
          {suffix && (
            <label
              htmlFor={inputId}
              className="cursor-text text-sm text-muted-foreground"
            >
              {suffix}
            </label>
          )}
        </div>
      </div>
      <Slider
        id={sliderId}
        value={[toNumber(localValue)]}
        onValueChange={(values) => {
          setLocalValue(values[0].toString());
        }}
        onValueCommit={(values) => onChange(Number(values[0]))}
        // used 'onLostPointerCapture' in addition to 'onValueCommit', because the last doesn't work properly for trackpads
        // details https://github.com/radix-ui/primitives/issues/1760
        onLostPointerCapture={() => {
          const valueToCommit = Number(localValue);
          if ((value ?? defaultValue) !== valueToCommit) {
            onChange(valueToCommit);
          }
        }}
        min={min}
        max={max}
        step={step}
      />
    </div>
  );
};

export default SliderInputControl;
