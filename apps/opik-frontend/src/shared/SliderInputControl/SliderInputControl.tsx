import { Label } from "@/ui/label";
import { Button } from "@/ui/button";
import { Info, RotateCcw } from "lucide-react";
import { Input } from "@/ui/input";
import toNumber from "lodash/toNumber";
import { Slider } from "@/ui/slider";
import { cn } from "@/lib/utils";
import React, { useEffect, useState } from "react";
import TooltipWrapper, {
  TooltipWrapperProps,
} from "@/shared/TooltipWrapper/TooltipWrapper";

const COMMIT_KEYS = ["Enter", "Tab", "Escape"];

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
  integer?: boolean;
  hint?: string;
}

const toShownValue = (value: number, integer?: boolean) =>
  (integer ? Math.floor(value) : value).toString();

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
  integer,
  hint,
}: SliderInputControlProps) => {
  const sliderId = `${id}-slider`;
  const inputId = `${id}-input`;

  const [localValue, setLocalValue] = useState(
    toShownValue(value ?? defaultValue, integer),
  );

  useEffect(() => {
    setLocalValue(toShownValue(value ?? defaultValue, integer));
  }, [value, defaultValue, integer]);

  const validateAndHandleChange = (value: string) => {
    const numVal = integer ? Math.floor(toNumber(value)) : toNumber(value);

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
    setLocalValue(integer ? numVal.toString() : value);
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
              <Info className="ml-1 size-3.5 text-light-slate" />
            </TooltipWrapper>
          )}
        </div>

        <div className="flex items-center">
          {!resetDisabled && (
            <Button
              type="button"
              variant="minimal"
              size="icon-sm"
              onClick={handleResetValue}
              className={cn({
                invisible: (value ?? defaultValue) === defaultValue,
              })}
            >
              <RotateCcw />
            </Button>
          )}
          <Input
            id={inputId}
            data-testid={inputId}
            className="box-content w-[var(--input-width)] max-w-[5ch] border px-2 py-0 text-right [&:not(:focus)]:border-transparent [&:not(:focus)]:px-0.5"
            style={
              {
                "--input-width": `${localValue?.length}ch`,
              } as React.CSSProperties
            }
            onChange={(event) => setLocalValue(event.target.value)}
            onBlur={(event) => validateAndHandleChange(event.target.value)}
            onKeyDown={(event) => {
              // Inside a Radix menu, Tab does not move focus and Escape unmounts the
              // menu without a blur, so onBlur alone would drop the typed value.
              if (COMMIT_KEYS.includes(event.key)) {
                validateAndHandleChange(event.currentTarget.value);
              }
            }}
            value={localValue || ""}
            dimension="xs"
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
      {hint && <p className="comet-body-xs mt-2 text-light-slate">{hint}</p>}
    </div>
  );
};

export default SliderInputControl;
