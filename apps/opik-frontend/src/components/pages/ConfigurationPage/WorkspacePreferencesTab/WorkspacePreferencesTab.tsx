import { useMemo } from "react";
import { ColumnPinningState } from "@tanstack/react-table";

import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  WORKSPACE_PREFERENCE_TYPE,
  WorkspacePreference,
  WorkspacePreferenceParam,
} from "./types";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import WorkspacePreferencesActionsCell from "./WorkspacePreferencesActionsCell";
import EditThreadTimeoutDialog from "./EditThreadTimeoutDialog";
import { useQueryParam } from "use-query-params";
import { useCallback } from "react";

export const DEFAULT_COLUMNS: ColumnData<WorkspacePreference>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "Preference",
    type: COLUMN_TYPE.string,
  },
  {
    id: "value",
    label: "Value",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_NAME_ID],
  right: [],
};

const WorkspacePreferencesTab = () => {
  const [editPreferenceOpen, setEditPreferenceOpen] = useQueryParam(
    "editPreference",
    WorkspacePreferenceParam,
    {
      updateType: "replaceIn",
    },
  );

  const data = [
    {
      name: "Thread timeout",
      value: "15 min",
      type: WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
    },
  ];

  const getPreferencesDialogConfig = (type: WORKSPACE_PREFERENCE_TYPE) => {
    const isOpen = editPreferenceOpen === type;
    const setOpen = (v: boolean) => setEditPreferenceOpen(v ? type : null);

    return {
      open: isOpen,
      setOpen,
    };
  };

  const onEdit = useCallback(
    (row: WorkspacePreference) => {
      setEditPreferenceOpen(row.type);
    },
    [setEditPreferenceOpen],
  );

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<WorkspacePreference, WorkspacePreference>(
        DEFAULT_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: WorkspacePreferencesActionsCell,
        customMeta: {
          onEdit,
        },
      }),
    ];
  }, [onEdit]);

  return (
    <>
      <DataTable
        columns={columns}
        data={data}
        columnPinning={DEFAULT_COLUMN_PINNING}
      />
      <EditThreadTimeoutDialog
        {...getPreferencesDialogConfig(
          WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
        )}
        defaultValue="15"
      />
    </>
  );
};

export default WorkspacePreferencesTab;
