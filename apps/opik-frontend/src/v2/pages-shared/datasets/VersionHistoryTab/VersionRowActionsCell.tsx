import React, { useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { Eye, Pencil, RotateCcw } from "lucide-react";
import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
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

  const openDialog = (dialog: number) => {
    setOpen(dialog);
    resetKeyRef.current = resetKeyRef.current + 1;
  };
  const rowActions: RowAction[] = [
    ...(!isLatestVersion
      ? [
          {
            id: "view",
            label: "View records",
            icon: Eye,
            onClick: () => onViewVersion(version),
          },
        ]
      : []),
    ...(canEdit
      ? [
          {
            id: "edit",
            label: "Edit",
            icon: Pencil,
            onClick: () => openDialog(EDIT_KEY),
          },
        ]
      : []),
    ...(canEdit && !isLatestVersion
      ? [
          {
            id: "restore",
            label: "Restore",
            icon: RotateCcw,
            onClick: () => openDialog(RESTORE_KEY),
          },
        ]
      : []),
  ];

  return (
    <>
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
      <RowActions actions={rowActions} />
    </>
  );
};

export default VersionRowActionsCell;
