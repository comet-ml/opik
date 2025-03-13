import React, { useRef } from "react";
import { map } from "lodash";
import { cn } from "@/lib/utils";
type ChildrenWidthMeasurerProps = {
  children: React.ReactNode;
  onMeasure: (result: number[]) => void;
  className?: string;
};
const ChildrenWidthMeasurer: React.FC<ChildrenWidthMeasurerProps> = ({
  children,
  onMeasure,
  className,
}) => {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const onContainerRef = (node: HTMLDivElement) => {
    if (node !== containerRef.current && node) {
      containerRef.current = node;
      onMeasure(map(node.children, (tag) => tag.getBoundingClientRect().width));
    }
  };

  if (containerRef.current) return;

  return (
    <div
      ref={onContainerRef}
      aria-hidden="true"
      className={cn(
        "invisible absolute flex size-full items-center justify-start gap-1.5 p-0 py-1",
        className,
      )}
    >
      {children}
    </div>
  );
};

export default ChildrenWidthMeasurer;
