import React, { useRef, useEffect, useState } from "react";
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
  const [hasMeasured, setHasMeasured] = useState(false);
  const childrenCount = React.Children.count(children);
  const prevChildrenCountRef = useRef(childrenCount);

  // reset measurement state when children count changes
  useEffect(() => {
    if (prevChildrenCountRef.current !== childrenCount) {
      prevChildrenCountRef.current = childrenCount;
      setHasMeasured(false);
      containerRef.current = null;
    }
  }, [childrenCount]);

  const onContainerRef = (node: HTMLDivElement | null) => {
    if (node && node !== containerRef.current) {
      containerRef.current = node;
      onMeasure(map(node.children, (tag) => tag.getBoundingClientRect().width));
      setHasMeasured(true);
    }
  };

  // after measurement, remove the invisible container from DOM to avoid extra nodes
  if (hasMeasured) {
    return null;
  }

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
