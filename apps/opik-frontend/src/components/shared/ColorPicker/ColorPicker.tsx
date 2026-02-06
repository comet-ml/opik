import React, { useCallback, useRef, useState } from "react";
import { Check, Pipette } from "lucide-react";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { HEX_COLOR_REGEX, PRESET_HEX_COLORS } from "@/constants/colorVariants";

interface ColorPickerProps {
  value: string;
  onChange: (color: string) => void;
}

const ColorPicker: React.FC<ColorPickerProps> = ({ value, onChange }) => {
  const [hexInput, setHexInput] = useState(value);
  const nativeInputRef = useRef<HTMLInputElement>(null);

  const handlePresetClick = useCallback(
    (color: string) => {
      onChange(color);
      setHexInput(color);
    },
    [onChange],
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
    <div className="flex w-52 flex-col gap-3">
      <div className="grid grid-cols-5 gap-2">
        {PRESET_HEX_COLORS.map((color) => (
          <button
            key={color}
            type="button"
            className={cn(
              "flex size-8 items-center justify-center rounded-md border-2 transition-colors",
              value.toLowerCase() === color.toLowerCase()
                ? "border-foreground"
                : "border-transparent hover:border-border",
            )}
            style={{ backgroundColor: color }}
            onClick={() => handlePresetClick(color)}
          >
            {value.toLowerCase() === color.toLowerCase() && (
              <Check className="size-4 text-white drop-shadow-[0_1px_1px_rgba(0,0,0,0.5)]" />
            )}
          </button>
        ))}
      </div>
      <Separator />
      <div className="flex items-center gap-2">
        <div
          className="relative size-8 shrink-0 cursor-pointer rounded-md border border-border"
          style={{ backgroundColor: isValidHex ? hexInput : value }}
          onClick={() => nativeInputRef.current?.click()}
        >
          <Pipette className="absolute inset-0 m-auto size-4 text-white drop-shadow-[0_1px_1px_rgba(0,0,0,0.5)]" />
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
          placeholder="#000000"
          className={cn(
            "h-8 font-mono text-xs",
            !isValidHex && hexInput !== "" && "border-destructive",
          )}
        />
      </div>
    </div>
  );
};

export default ColorPicker;
