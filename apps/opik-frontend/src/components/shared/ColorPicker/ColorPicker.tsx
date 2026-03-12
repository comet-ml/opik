import React, { useCallback, useRef, useState } from "react";
import { Check } from "lucide-react";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { HEX_COLOR_REGEX, PRESET_HEX_COLORS } from "@/constants/colorVariants";

interface ColorPickerProps {
  value: string;
  onChange: (color: string) => void;
  onPresetSelect?: (color: string) => void;
}

const ColorPicker: React.FC<ColorPickerProps> = ({
  value,
  onChange,
  onPresetSelect,
}) => {
  const [hexInput, setHexInput] = useState(value);
  const nativeInputRef = useRef<HTMLInputElement>(null);

  const handlePresetClick = useCallback(
    (color: string) => {
      onChange(color);
      setHexInput(color);
      onPresetSelect?.(color);
    },
    [onChange, onPresetSelect],
  );

  const handleHexInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const val = e.target.value;
      setHexInput(val);
      if (HEX_COLOR_REGEX.test(val)) {
        onChange(val);
      }
    },
    [onChange],
  );

  const handleHexKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLInputElement>) => {
      if (e.key === "Enter" && HEX_COLOR_REGEX.test(hexInput)) {
        onPresetSelect?.(hexInput);
      }
    },
    [hexInput, onPresetSelect],
  );

  const handleNativeChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const color = e.target.value;
      onChange(color);
      setHexInput(color);
    },
    [onChange],
  );

  const isValidHex = HEX_COLOR_REGEX.test(hexInput);

  return (
    <div className="flex flex-col">
      <div className="grid w-[144px] grid-cols-5 gap-1.5">
        {PRESET_HEX_COLORS.map((color) => {
          const isSelected = value.toLowerCase() === color.toLowerCase();
          return (
            <button
              key={color}
              type="button"
              className="flex size-6 items-center justify-center rounded transition-[filter] hover:brightness-90"
              style={{ backgroundColor: color }}
              onClick={() => handlePresetClick(color)}
            >
              {isSelected && <Check className="size-3 text-white" />}
            </button>
          );
        })}
      </div>
      <Separator className="my-3" />
      <div className="flex w-[144px] items-center gap-1.5">
        <div
          className="size-6 shrink-0 cursor-pointer rounded"
          style={{ backgroundColor: isValidHex ? hexInput : value }}
          onClick={() => nativeInputRef.current?.click()}
        >
          <input
            ref={nativeInputRef}
            type="color"
            value={isValidHex ? hexInput : value}
            onChange={handleNativeChange}
            className="sr-only"
          />
        </div>
        <Input
          value={hexInput}
          onChange={handleHexInputChange}
          onKeyDown={handleHexKeyDown}
          placeholder="#000000"
          className={cn(
            "h-6 font-mono text-xs",
            !isValidHex &&
              hexInput !== "" &&
              "border-destructive focus-visible:border-destructive",
          )}
        />
      </div>
    </div>
  );
};

export default ColorPicker;
