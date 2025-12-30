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
import { isLatestVersionTag } from "@/constants/datasets";
import useRestoreDatasetVersionMutation from "@/api/datasets/useRestoreDatasetVersionMutation";
import EditVersionDialog from "./EditVersionDialog";

type CustomMeta = {
  datasetId: string;
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
  const { datasetId } = (custom ?? {}) as CustomMeta;

  const restoreMutation = useRestoreDatasetVersionMutation();

  const isLatestVersion = version.tags?.some(isLatestVersionTag) ?? false;

  const handleRestore = () => {
    restoreMutation.mutate({
      datasetId,
      versionRef: version.version_hash,
    });
    setOpen(false);
  };

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
        datasetId={datasetId}
      />

      <ConfirmDialog
        key={`restore-${resetKeyRef.current}`}
        open={open === RESTORE_KEY}
        setOpen={setOpen}
        onConfirm={handleRestore}
        title="Restore version"
        description={`Restoring this version will create a new version based on version ${version.version_name}. All previous versions will stay in your history.`}
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
          {!isLatestVersion && (
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
    </CellWrapper>
  );
};

export default VersionRowActionsCell;
