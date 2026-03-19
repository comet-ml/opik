import React from "react";
import { cn } from "@/lib/utils";
import { ExperimentItem } from "@/types/datasets";
import { ExperimentItemStatus } from "@/types/evaluation-suites";

type MultiRunTabsProps = {
  experimentItems: ExperimentItem[];
  renderRunContent: (item: ExperimentItem, idx: number) => React.ReactNode;
  activeIndex: number;
  onActiveIndexChange: (index: number) => void;
};

const MultiRunTabs: React.FC<MultiRunTabsProps> = ({
  experimentItems,
  renderRunContent,
  activeIndex,
  onActiveIndexChange,
}) => {
  if (experimentItems.length <= 1) {
    return experimentItems[0] ? (
      <div className="flex min-h-0 flex-1 flex-col">
        {renderRunContent(experimentItems[0], 0)}
      </div>
    ) : null;
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <div className="flex w-fit max-w-full shrink-0 items-center overflow-x-auto rounded-md border border-border p-1">
        {experimentItems.map((item, idx) => {
          const isPassed = item.status === ExperimentItemStatus.PASSED;
          const dotColor = isPassed
            ? "var(--special-button)"
            : "var(--template-icon-performance)";

          return (
            <button
              key={idx}
              type="button"
              onClick={() => onActiveIndexChange(idx)}
              className={cn(
                "flex shrink-0 items-center gap-1.5 rounded px-2 py-1 comet-body-s",
                activeIndex === idx && "bg-[var(--separator-light)]",
              )}
            >
              <span
                className="size-[7px] shrink-0 rounded-[1.5px]"
                style={{ backgroundColor: dotColor }}
              />
              Run {idx + 1}
            </button>
          );
        })}
      </div>
      <div className="flex min-h-0 flex-1 flex-col">
        {renderRunContent(experimentItems[activeIndex], activeIndex)}
      </div>
    </div>
  );
};

export default MultiRunTabs;
