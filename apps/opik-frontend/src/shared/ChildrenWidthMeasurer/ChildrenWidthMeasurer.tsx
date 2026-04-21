import React, {
  useEffect,
  useRef,
  useState,
  useMemo,
  ReactElement,
} from "react";
import map from "lodash/map";
import { cn } from "@/lib/utils";

type ChildrenWidthMeasurerProps = {
  children: React.ReactNode;
  onMeasure: (result: number[]) => void;
  className?: string;
};

const measureChildren = (node: HTMLElement): number[] =>
  map(node.children, (tag) => tag.getBoundingClientRect().width);

const hasValidWidths = (widths: number[]) =>
  widths.length > 0 && widths.some((w) => w > 0);

const ChildrenWidthMeasurer: React.FC<ChildrenWidthMeasurerProps> = ({
  children,
  onMeasure,
  className,
}) => {
  const [node, setNode] = useState<HTMLDivElement | null>(null);
  const [hasMeasured, setHasMeasured] = useState(false);

  const childrenSignature = useMemo(() => {
    return React.Children.toArray(children)
      .map((c) => (c as ReactElement)?.key ?? "")
      .join("|");
  }, [children]);

  const prevSignatureRef = useRef(childrenSignature);

  // reset only when children actually change, not on initial mount
  useEffect(() => {
    if (prevSignatureRef.current !== childrenSignature) {
      prevSignatureRef.current = childrenSignature;
      setHasMeasured(false);
      setNode(null);
    }
  }, [childrenSignature]);

  // measure immediately or observe until visible
  useEffect(() => {
    if (!node || hasMeasured) return;

    const widths = measureChildren(node);
    if (hasValidWidths(widths)) {
      onMeasure(widths);
      setHasMeasured(true);
      return;
    }

    // element is inside display:none — retry when it becomes visible
    const observer = new ResizeObserver(() => {
      const retryWidths = measureChildren(node);
      if (hasValidWidths(retryWidths)) {
        onMeasure(retryWidths);
        setHasMeasured(true);
      }
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, [node, hasMeasured, onMeasure]);

  if (hasMeasured) {
    return null;
  }

  return (
    <div
      ref={setNode}
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
