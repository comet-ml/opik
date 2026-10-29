import React, { useEffect, useRef, useState } from "react";

import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import { TOOLTIP_DELAY_DURATION } from "@/constants/shared";

const FIELD_SELECTOR = "input, [role=combobox]";

type CutField = {
  text: string;
  top: number;
  left: number;
  width: number;
  height: number;
};

const getCutText = (field: Element) => {
  if (field instanceof HTMLInputElement) {
    return field.scrollWidth > field.clientWidth ? field.value : "";
  }
  const label = field.querySelector(":scope > span");
  return label && label.scrollWidth > label.clientWidth
    ? label.textContent ?? ""
    : "";
};

const FieldOverflowTooltip: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const fieldRef = useRef<Element | null>(null);
  const timerRef = useRef<number>();
  const [cutField, setCutField] = useState<CutField | null>(null);

  useEffect(() => () => window.clearTimeout(timerRef.current), []);

  const hide = () => {
    window.clearTimeout(timerRef.current);
    setCutField(null);
  };

  const handleMouseOver = (event: React.MouseEvent) => {
    const field = (event.target as Element).closest(FIELD_SELECTOR);
    if (field === fieldRef.current) return;
    fieldRef.current = field;
    hide();

    const text = field ? getCutText(field) : "";
    if (!field || !text || !containerRef.current) return;

    const box = field.getBoundingClientRect();
    const origin = containerRef.current.getBoundingClientRect();
    timerRef.current = window.setTimeout(
      () =>
        setCutField({
          text,
          top: box.top - origin.top,
          left: box.left - origin.left,
          width: box.width,
          height: box.height,
        }),
      TOOLTIP_DELAY_DURATION,
    );
  };

  const handleMouseLeave = () => {
    fieldRef.current = null;
    hide();
  };

  return (
    <div
      ref={containerRef}
      className="relative min-w-0 flex-1"
      onMouseOver={handleMouseOver}
      onMouseLeave={handleMouseLeave}
      onPointerDown={hide}
    >
      {children}
      {cutField && (
        <Tooltip open>
          <TooltipTrigger asChild>
            <div
              aria-hidden
              className="pointer-events-none absolute"
              style={{
                top: cutField.top,
                left: cutField.left,
                width: cutField.width,
                height: cutField.height,
              }}
            />
          </TooltipTrigger>
          <TooltipPortal>
            <TooltipContent collisionPadding={16}>
              {cutField.text}
            </TooltipContent>
          </TooltipPortal>
        </Tooltip>
      )}
    </div>
  );
};

export default FieldOverflowTooltip;
