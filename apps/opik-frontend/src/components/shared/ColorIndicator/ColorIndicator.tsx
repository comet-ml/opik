import React, { useCallback, useState } from "react";
import { cva, type VariantProps } from "class-variance-authority";

import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import ColorPicker from "@/components/shared/ColorPicker/ColorPicker";
import useUpdateColorMapping from "@/hooks/useUpdateColorMapping";
import { resolveHexColor } from "@/lib/colorVariants";
import { cn } from "@/lib/utils";

const colorIndicatorVariants = cva("bg-[var(--bg-color)]", {
  variants: {
    variant: {
      square: "rounded-[0.15rem] p-1",
      dot: "size-1.5 rounded-full border-[var(--color-border)]",
    },
  },
});

type ColorIndicatorProps = VariantProps<typeof colorIndicatorVariants> & {
  label: string;
  colorKey?: string;
  color: string;
  className?: string;
  readOnly?: boolean;
};

const ColorIndicator: React.FC<ColorIndicatorProps> = ({
  label,
  colorKey,
  color,
  variant,
  className,
  readOnly = false,
}) => {
  const [popoverOpen, setPopoverOpen] = useState(false);
  const { updateColor, previewColor, setPreviewColor } =
    useUpdateColorMapping();

  const effectiveColorKey = colorKey ?? label;

  const handleColorChange = useCallback(
    (hex: string) => {
      setPreviewColor((prev) => ({ ...prev, [effectiveColorKey]: hex }));
    },
    [effectiveColorKey, setPreviewColor],
  );

  const handlePresetSelect = useCallback(
    (hex: string) => {
      updateColor(effectiveColorKey, hex);
      setPopoverOpen(false);
    },
    [effectiveColorKey, updateColor],
  );

  const handleOpenChange = useCallback(
    (open: boolean) => {
      if (!open && effectiveColorKey in previewColor) {
        updateColor(effectiveColorKey, resolveHexColor(color));
      }
      setPopoverOpen(open);
    },
    [color, effectiveColorKey, updateColor, previewColor],
  );

  const colorStyle = {
    "--bg-color": color,
    "--color-bg": color,
    "--color-border": color,
  } as React.CSSProperties;

  const classes = cn(colorIndicatorVariants({ variant }), className);

  if (readOnly) {
    return <div className={classes} style={colorStyle} />;
  }

  return (
    <Popover open={popoverOpen} onOpenChange={handleOpenChange} modal>
      <Tooltip open={popoverOpen ? false : undefined}>
        <TooltipTrigger asChild>
          <PopoverTrigger asChild>
            <div
              className={classes}
              style={{ ...colorStyle, cursor: "pointer" }}
              onClick={(e) => e.stopPropagation()}
            />
          </PopoverTrigger>
        </TooltipTrigger>
        <TooltipPortal>
          <TooltipContent>Click to change color</TooltipContent>
        </TooltipPortal>
      </Tooltip>
      <PopoverContent
        side="bottom"
        align="start"
        className="w-auto p-4"
        onPointerMove={(e) => e.stopPropagation()}
        onMouseMove={(e) => e.stopPropagation()}
        onMouseEnter={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
        onDoubleClick={(e) => e.stopPropagation()}
      >
        <ColorPicker
          value={resolveHexColor(color)}
          onChange={handleColorChange}
          onPresetSelect={handlePresetSelect}
        />
      </PopoverContent>
    </Popover>
  );
};

export default ColorIndicator;
