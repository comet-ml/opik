import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";

import { Button } from "@/ui/button";
import { Environment } from "@/types/environments";
import useEnvironmentBatchDeleteMutation from "@/api/environments/useEnvironmentBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type EnvironmentsActionsPanelProps = {
  environments: Environment[];
};

const EnvironmentsActionsPanel: React.FunctionComponent<
  EnvironmentsActionsPanelProps
> = ({ environments }) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !environments?.length;

  const { mutate } = useEnvironmentBatchDeleteMutation();

  const deleteEnvironmentsHandler = useCallback(() => {
    mutate({ ids: environments.map((e) => e.id) });
  }, [environments, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteEnvironmentsHandler}
        title="Delete environments"
        description="This action can’t be undone. Existing traces and spans will keep their environment values and surface under “Unrecognized environments”. Are you sure you want to continue?"
        confirmText="Delete environments"
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

export default EnvironmentsActionsPanel;
