import React, { useRef, useState } from "react";
import { Plus } from "lucide-react";
import { HeaderContext } from "@tanstack/react-table";

import { Button } from "@/components/ui/button";
import { ExperimentsCompare } from "@/types/datasets";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import AddExperimentToCompareDialog from "@/components/pages/DatasetCompareExperimentsPage/AddExperimentToCompareDialog";

export const DatasetCompareAddExperimentHeader: React.FunctionComponent<
  HeaderContext<ExperimentsCompare, unknown>
> = () => {
  const datasetId = useDatasetIdFromURL();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  return (
    <div
      className="absolute inset-0 flex items-center justify-center"
      onClick={(e) => e.stopPropagation()}
    >
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
