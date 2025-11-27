import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import { Alert } from "@/types/alerts";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import useAlertsBatchDeleteMutation from "@/api/alerts/useAlertsBatchDeleteMutation";
import useAppStore from "@/store/AppStore";

const AlertsRowActionsCell: React.FunctionComponent<
  CellContext<Alert, unknown>
> = (context) => {
  const resetKeyRef = useRef(0);
  const alert = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { mutate } = useAlertsBatchDeleteMutation();

  const deleteAlertHandler = useCallback(() => {
    if (!alert.id) return;

    mutate({ ids: [alert.id] });
  }, [alert.id, mutate]);

  const handleEditClick = useCallback(() => {
    if (!alert.id) return;

    navigate({
      to: "/$workspaceName/alerts/$alertId",
      params: { workspaceName, alertId: alert.id },
      search: (prev) => prev, // Preserve existing search params
    });
  }, [navigate, workspaceName, alert.id]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteAlertHandler}
        title="Delete alert"
        description="Are you sure you want to delete this alert? This action cannot be undone."
        confirmText="Delete alert"
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={handleEditClick}>
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Trash className="mr-2 size-4" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default AlertsRowActionsCell;
