import React from "react";
import { CheckCircle2, Info } from "lucide-react";
import { Button } from "@/components/ui/button";

export interface SelectAllBannerProps {
  selectedCount: number;
  totalCount: number;
  onSelectAll: () => void;
  onClearSelection: () => void;
}

const SelectAllBanner: React.FC<SelectAllBannerProps> = ({
  selectedCount,
  totalCount,
  onSelectAll,
  onClearSelection,
}) => {
  const isAllSelected = selectedCount === totalCount;

  return (
    <div className="mb-4 flex items-center justify-between rounded-md border border-blue-200 bg-blue-50 p-4">
      <div className="flex items-center gap-2">
        {isAllSelected ? (
          <CheckCircle2 className="size-4 text-[var(--color-blue)]" />
        ) : (
          <Info className="size-4 text-[var(--color-blue)]" />
        )}
        <span className="comet-body-s text-foreground">
          {isAllSelected ? (
            <>
              All <b>{totalCount}</b> items are selected
            </>
          ) : (
            <>
              All <b>{selectedCount}</b> items on this page are selected.{" "}
              <Button
                variant="link"
                size="sm"
                onClick={onSelectAll}
                className="h-auto p-0"
              >
                Select all {totalCount} items?
              </Button>
            </>
          )}
        </span>
      </div>
      <Button
        variant="ghost"
        size="sm"
        onClick={onClearSelection}
        className="h-auto p-0"
      >
        Clear selection
      </Button>
    </div>
  );
};

export default SelectAllBanner;
