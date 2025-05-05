import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Optimization } from "@/types/optimizations";
import useOptimizationBatchDeleteMutation from "@/api/optimizations/useOptimizationBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type OptimizationsActionsPanelsProps = {
  optimizations: Optimization[];
};

const OptimizationsActionsPanel: React.FunctionComponent<
  OptimizationsActionsPanelsProps
> = ({ optimizations }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !optimizations?.length;

  const { mutate } = useOptimizationBatchDeleteMutation();

  const deleteOptimizationsHandler = useCallback(() => {
    mutate({
      ids: optimizations.map((o) => o.id),
    });
  }, [optimizations, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteOptimizationsHandler}
        title="Delete optimizations"
        description="Are you sure you want to delete all selected optimizations?"
        confirmText="Delete optimizations"
      />
      <TooltipWrapper content="Delete">
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default OptimizationsActionsPanel;
