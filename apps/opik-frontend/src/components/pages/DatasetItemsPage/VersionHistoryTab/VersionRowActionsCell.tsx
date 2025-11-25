import React, { useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, RotateCcw } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Button } from "@/components/ui/button";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import { DatasetVersion } from "@/types/datasets";
import EditVersionDialog from "./EditVersionDialog";

const EDIT_KEY = 1;
const RESTORE_KEY = 2;

const VersionRowActionsCell: React.FC<CellContext<DatasetVersion, unknown>> = (
  context,
) => {
  const resetKeyRef = useRef(0);
  const version = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <EditVersionDialog
        key={`edit-${resetKeyRef.current}`}
        open={open === EDIT_KEY}
        setOpen={setOpen}
        version={version}
      />

      <ConfirmDialog
        key={`restore-${resetKeyRef.current}`}
        open={open === RESTORE_KEY}
        setOpen={setOpen}
        onConfirm={() => {}}
        title="Restore version"
        description={`Restoring this version will create a new version based on version ${version.version_hash}. All previous versions will stay in your history.`}
        confirmText="Restore version"
        confirmButtonVariant="default"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(EDIT_KEY);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => {
              setOpen(RESTORE_KEY);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <RotateCcw className="mr-2 size-4" />
            Restore
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default VersionRowActionsCell;
