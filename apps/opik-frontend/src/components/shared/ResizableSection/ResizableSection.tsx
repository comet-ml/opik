import React, { useCallback } from "react";
import { Resizable, ResizeCallback } from "re-resizable";
import useLocalStorageState from "use-local-storage-state";

interface ResizableSectionProps {
  storageKey: string;
  children: React.ReactNode;
  className?: string;
  minHeight?: number;
  minPercentage?: number;
  maxPercentage?: number;
}

const ResizableSection: React.FC<ResizableSectionProps> = ({
  storageKey,
  children,
  className,
  minHeight = 60,
  minPercentage = 10,
  maxPercentage = 90,
}) => {
  const [savedHeight, setSavedHeight] = useLocalStorageState<number | null>(
    storageKey,
    { defaultValue: null },
  );

  const onResizeStop: ResizeCallback = useCallback(
    (_e, _direction, ref) => {
      const parent = ref.parentElement;
      if (!parent) return;

      const parentHeight = parent.clientHeight;
      if (parentHeight === 0) return;

      const percentage = Math.min(
        Math.max((ref.clientHeight / parentHeight) * 100, minPercentage),
        maxPercentage,
      );
      setSavedHeight(percentage);
    },
    [setSavedHeight, minPercentage, maxPercentage],
  );

  const sizeProps =
    savedHeight !== null
      ? { size: { height: `${savedHeight}%` } }
      : { defaultSize: { height: "auto" } };

  return (
    <Resizable
      {...sizeProps}
      className={className}
      enable={{ bottom: true }}
      minHeight={minHeight}
      maxHeight={`${maxPercentage}%`}
      bounds="parent"
      onResizeStop={onResizeStop}
    >
      {children}
    </Resizable>
  );
};

export default ResizableSection;
