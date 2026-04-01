import React from "react";
import { SearchX } from "lucide-react";
import { Button } from "@/ui/button";

type DataTableNoMatchingDataProps = {
  onClearFilters?: () => void;
};

const DataTableNoMatchingData: React.FC<DataTableNoMatchingDataProps> = ({
  onClearFilters,
}) => {
  return (
    <div className="sticky left-0 flex min-h-[50vh] w-[var(--scroll-body-client-width,100%)] items-center justify-center bg-background">
      <div className="flex flex-col items-center gap-2">
        <SearchX className="mb-1 size-5 text-muted-slate" />
        <h3 className="comet-body">No matching results</h3>
        <p className="comet-body-s text-muted-slate">
          Try adjusting your filters or search query.
        </p>
        {onClearFilters && (
          <Button variant="link" size="sm" onClick={onClearFilters}>
            Clear filters
          </Button>
        )}
      </div>
    </div>
  );
};

export default DataTableNoMatchingData;
