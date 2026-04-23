import React, { useEffect } from "react";
import { X } from "lucide-react";
import usePageBodyScrollContainer from "@/contexts/usePageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";

type SelectionActionBarProps = {
  selectedCount: number;
  onDeselectAll: () => void;
  children: React.ReactNode;
};

const SelectionActionBar: React.FC<SelectionActionBarProps> = ({
  selectedCount,
  onDeselectAll,
  children,
}) => {
  const { recalculateOffsets } = usePageBodyScrollContainer();

  useEffect(() => {
    recalculateOffsets();
    return () => recalculateOffsets();
  }, [recalculateOffsets]);

  return (
    <PageBodyStickyContainer
      className="py-2"
      direction="bidirectional"
      limitWidth
    >
      <div className="comet-body-s flex h-10 w-full items-center justify-between rounded-md bg-selection-bar px-4 text-white">
        <div className="flex items-center gap-3">
          <span>Selected: {selectedCount}</span>
          <button
            className="text-white/80 ml-1 flex items-center gap-1 hover:text-white"
            onClick={onDeselectAll}
          >
            <X className="mr-0.5 size-3.5" />
            Deselect all
          </button>
        </div>
        <div className="flex items-center gap-1">{children}</div>
      </div>
    </PageBodyStickyContainer>
  );
};

export default SelectionActionBar;
