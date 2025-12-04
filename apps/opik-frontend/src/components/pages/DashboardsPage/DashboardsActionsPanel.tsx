import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Dashboard } from "@/types/dashboard";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type DashboardsActionsPanelsProps = {
  dashboards: Dashboard[];
};

const DashboardsActionsPanel: React.FunctionComponent<
  DashboardsActionsPanelsProps
> = ({ dashboards }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !dashboards?.length;

  const { mutate } = useDashboardBatchDeleteMutation();

  const deleteDashboardsHandler = useCallback(() => {
    mutate({
      ids: dashboards.map((d) => d.id),
    });
  }, [dashboards, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteDashboardsHandler}
        title="Delete dashboards"
        description="Deleting dashboards will also remove all their widgets and sections. This action can't be undone. Are you sure you want to continue?"
        confirmText="Delete dashboards"
        confirmButtonVariant="destructive"
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

export default DashboardsActionsPanel;
