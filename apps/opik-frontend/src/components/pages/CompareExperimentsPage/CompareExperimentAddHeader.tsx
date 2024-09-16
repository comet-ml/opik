import React, { useRef, useState } from "react";
import { Plus } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import { Button } from "@/components/ui/button";
import { ExperimentsCompare } from "@/types/datasets";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
import AddExperimentToCompareDialog from "@/components/pages/CompareExperimentsPage/AddExperimentToCompareDialog";

export const CompareExperimentAddHeader: React.FunctionComponent<
  HeaderContext<ExperimentsCompare, unknown>
> = (context) => {
  const datasetId = useDatasetIdFromCompareExperimentsURL();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const hasData = context.table.getRowCount() > 0;

  return (
    <div
      className="absolute inset-0 flex items-center justify-center px-2"
      onClick={(e) => e.stopPropagation()}
    >
      {hasData && (
        <div className="absolute left-0 top-0 h-[10000px] w-px bg-border"></div>
      )}
      <AddExperimentToCompareDialog
        datasetId={datasetId}
        open={open}
        setOpen={setOpen}
      />
      <Button
        variant="ghost"
        size="icon"
        onClick={() => {
          setOpen(true);
          resetKeyRef.current = resetKeyRef.current + 1;
        }}
      >
        <Plus className="size-4" />
      </Button>
    </div>
  );
};

export default CompareExperimentAddHeader;
