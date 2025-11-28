import React from "react";
import { CheckCircle2, Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

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

  const bannerClasses = cn(
    "mb-4 flex items-center justify-between rounded-md border p-4",
    isAllSelected
      ? "border-green-200 bg-green-50"
      : "border-blue-200 bg-blue-50",
  );

  const iconClasses = cn(
    "size-4",
    isAllSelected ? "text-green-600" : "text-blue-600",
  );

  return (
    <div className={bannerClasses}>
      <div className="flex items-center gap-2">
        {isAllSelected ? (
          <CheckCircle2 className={iconClasses} />
        ) : (
          <Info className={iconClasses} />
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
        variant="link"
        size="sm"
        onClick={onClearSelection}
        className="h-auto p-0 text-primary"
      >
        Clear selection
      </Button>
    </div>
  );
};

export default SelectAllBanner;
