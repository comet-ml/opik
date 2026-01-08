import React, {
  useRef,
  useEffect,
  useState,
  useMemo,
  ReactElement,
} from "react";
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
  const prevChildrenSignatureRef = useRef<string>("");

  // compute a stable signature based on children keys/content
  const childrenSignature = useMemo(() => {
    return React.Children.toArray(children)
      .map((c) => (c as ReactElement)?.key ?? "")
      .join("|");
  }, [children]);

  // reset measurement state when children signature changes
  useEffect(() => {
    if (prevChildrenSignatureRef.current !== childrenSignature) {
      prevChildrenSignatureRef.current = childrenSignature;
      setHasMeasured(false);
      containerRef.current = null;
    }
  }, [childrenSignature]);

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
