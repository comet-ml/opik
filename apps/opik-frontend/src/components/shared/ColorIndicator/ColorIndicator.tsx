import React, { useCallback, useState } from "react";
import { RotateCcw } from "lucide-react";
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
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useUpdateColorMapping from "@/hooks/useUpdateColorMapping";
import { resolveHexColor } from "@/constants/colorVariants";
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
  const { updateColor, resetColor } = useUpdateColorMapping();

  const effectiveKey = colorKey ?? label;

  const handleColorChange = useCallback(
    (hex: string) => {
      updateColor(effectiveKey, hex);
    },
    [effectiveKey, updateColor],
  );

  const handleReset = useCallback(() => {
    resetColor(effectiveKey);
    setPopoverOpen(false);
  }, [effectiveKey, resetColor]);

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
    <Popover open={popoverOpen} onOpenChange={setPopoverOpen} modal>
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
        className="w-auto p-3"
        onPointerMove={(e) => e.stopPropagation()}
        onMouseMove={(e) => e.stopPropagation()}
        onMouseEnter={(e) => e.stopPropagation()}
        onClick={(e) => e.stopPropagation()}
        onDoubleClick={(e) => e.stopPropagation()}
      >
        <ColorPicker
          value={resolveHexColor(color)}
          onChange={handleColorChange}
        />
        <Separator className="my-3" />
        <Button
          variant="ghost"
          size="sm"
          className="w-full justify-start gap-2"
          onClick={handleReset}
        >
          <RotateCcw className="size-3.5" />
          Reset to default
        </Button>
      </PopoverContent>
    </Popover>
  );
};

export default ColorIndicator;
