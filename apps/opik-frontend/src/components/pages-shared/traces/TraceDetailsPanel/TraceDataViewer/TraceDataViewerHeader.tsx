import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import React, { useRef, useState } from "react";
import { ButtonLayoutSize } from "@/components/pages-shared/traces/DetailsActionSection";

const LAYOUT_CONSTANTS = {
  MIN_SPACE_BETWEEN: 200,
  EXPANDED_PANEL_WIDTH: 520,
  get REQUIRED_WIDTH() {
    return this.MIN_SPACE_BETWEEN + this.EXPANDED_PANEL_WIDTH;
  },
};

type TraceDataViewerHeaderProps = {
  title: React.ReactNode;
  actionsPanel: (size: ButtonLayoutSize) => React.ReactNode;
};
const TraceDataViewerHeader: React.FC<TraceDataViewerHeaderProps> = ({
  title,
  actionsPanel,
}) => {
  const titleRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<ButtonLayoutSize>(ButtonLayoutSize.Small);

  const { ref: containerRef } = useObserveResizeNode<HTMLDivElement>((node) => {
    if (!titleRef.current) return;

    const titleWidth = titleRef.current.clientWidth;
    const availableSpace =
      node.clientWidth - titleWidth - LAYOUT_CONSTANTS.REQUIRED_WIDTH;
    const newSize: ButtonLayoutSize =
      availableSpace > 0 ? ButtonLayoutSize.Large : ButtonLayoutSize.Small;

    if (newSize !== size) {
      setSize(newSize);
    }
  });

  return (
    <div
      ref={containerRef}
      className="flex w-full items-center justify-between gap-2 overflow-x-hidden"
    >
      <div ref={titleRef} className="flex items-center gap-2 overflow-x-hidden">
        {title}
      </div>
      <div className="flex flex-nowrap gap-2">{actionsPanel(size)}</div>
    </div>
  );
};

export default TraceDataViewerHeader;
