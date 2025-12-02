import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Alert } from "@/types/alerts";
import useAlertsBatchDeleteMutation from "@/api/alerts/useAlertsBatchDeleteMutation";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type AlertsActionsPanelsProps = {
  alerts: Alert[];
};

const AlertsActionsPanel: React.FunctionComponent<AlertsActionsPanelsProps> = ({
  alerts,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !alerts?.length;

  const { mutate } = useAlertsBatchDeleteMutation();

  const deleteAlertsHandler = useCallback(() => {
    mutate({
      ids: alerts.map((a) => a.id!),
    });
  }, [alerts, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteAlertsHandler}
        title="Delete alerts"
        description="Are you sure you want to delete these alerts? This action cannot be undone."
        confirmText="Delete alerts"
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
          <Trash className="size-4" />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default AlertsActionsPanel;
