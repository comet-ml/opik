import React, { useEffect } from "react";
import { X } from "lucide-react";
import { cn } from "@/lib/utils";
import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";

type SelectionActionBarProps = {
  selectedCount: number;
  onDeselectAll: () => void;
  children: React.ReactNode;
  // Hosts swap their toolbar row for this bar, so they pass the row's
  // vertical padding to keep the table below from shifting.
  className?: string;
  // Off for hosts outside the page body scroll (e.g. sidebars), where the
  // sticky container's page-width sizing doesn't apply.
  sticky?: boolean;
};

const SelectionActionBar: React.FC<SelectionActionBarProps> = ({
  selectedCount,
  onDeselectAll,
  children,
  className,
  sticky = true,
}) => {
  const { recalculateOffsets } = usePageBodyScrollContainer();

  useEffect(() => {
    recalculateOffsets();
    return () => recalculateOffsets();
  }, [recalculateOffsets]);

  const bar = (
    <div className="comet-body-xs flex h-8 w-full items-center justify-between rounded-md bg-selection-bar px-4 text-white">
      <div className="flex items-center gap-3">
        <span>Selected: {selectedCount}</span>
        <button
          className="text-white/80 ml-1 flex items-center gap-1 font-medium hover:text-white"
          onClick={onDeselectAll}
        >
          <X className="mr-0.5 size-3" />
          Deselect all
        </button>
      </div>
      <div className="flex items-center gap-1">{children}</div>
    </div>
  );

  if (!sticky) {
    return <div className={cn("py-2", className)}>{bar}</div>;
  }

  return (
    <PageBodyStickyContainer
      className={cn("py-2", className)}
      direction="bidirectional"
      limitWidth
    >
      {bar}
    </PageBodyStickyContainer>
  );
};

export default SelectionActionBar;
