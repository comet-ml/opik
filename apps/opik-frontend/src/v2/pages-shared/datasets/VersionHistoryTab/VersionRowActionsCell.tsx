import React, { useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Eye, MoreHorizontal, Pencil, RotateCcw } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { DatasetVersion } from "@/types/datasets";
import { isLatestVersionTag } from "@/constants/datasets";
import EditVersionDialog from "./EditVersionDialog";
import RestoreVersionDialog from "./RestoreVersionDialog";

type CustomMeta = {
  datasetId: string;
  canEdit: boolean;
  onViewVersion: (version: DatasetVersion) => void;
};

const EDIT_KEY = 1;
const RESTORE_KEY = 2;

const VersionRowActionsCell: React.FC<CellContext<DatasetVersion, unknown>> = (
  context,
) => {
  const resetKeyRef = useRef(0);
  const version = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { custom } = context.column.columnDef.meta ?? {};
  const { datasetId, canEdit, onViewVersion } = (custom ?? {}) as CustomMeta;

  const isLatestVersion = version.tags?.some(isLatestVersionTag) ?? false;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      {canEdit && (
        <>
          <EditVersionDialog
            key={`edit-${resetKeyRef.current}`}
            open={open === EDIT_KEY}
            setOpen={setOpen}
            version={version}
            datasetId={datasetId}
          />
          <RestoreVersionDialog
            key={`restore-${resetKeyRef.current}`}
            open={open === RESTORE_KEY}
            setOpen={setOpen}
            datasetId={datasetId}
            version={version}
          />
        </>
      )}
      {(canEdit || !isLatestVersion) && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="minimal" size="icon" className="-mr-2.5">
              <span className="sr-only">Actions menu</span>
              <MoreHorizontal className="size-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-52">
            {!isLatestVersion && (
              <DropdownMenuItem onClick={() => onViewVersion(version)}>
                <Eye className="mr-2 size-4" />
                View records
              </DropdownMenuItem>
            )}
            {canEdit && (
              <DropdownMenuItem
                onClick={() => {
                  setOpen(EDIT_KEY);
                  resetKeyRef.current = resetKeyRef.current + 1;
                }}
              >
                <Pencil className="mr-2 size-4" />
                Edit
              </DropdownMenuItem>
            )}
            {canEdit && !isLatestVersion && (
              <DropdownMenuItem
                onClick={() => {
                  setOpen(RESTORE_KEY);
                  resetKeyRef.current = resetKeyRef.current + 1;
                }}
              >
                <RotateCcw className="mr-2 size-4" />
                Restore
              </DropdownMenuItem>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </CellWrapper>
  );
};

export default VersionRowActionsCell;
