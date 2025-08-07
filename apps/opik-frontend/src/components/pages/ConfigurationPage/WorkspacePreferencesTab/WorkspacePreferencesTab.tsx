import { useMemo, useCallback } from "react";
import { useQueryParam } from "use-query-params";

import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import Loader from "@/components/shared/Loader/Loader";
import useWorkspaceConfig from "@/api/workspaces/useWorkspaceConfig";
import { formatIso8601Duration } from "@/lib/date";

import {
  WorkspacePreference,
  WORKSPACE_PREFERENCE_TYPE,
  WorkspacePreferenceParam,
} from "./types";
import {
  WORKSPACE_PREFERENCES_DEFAULT_COLUMNS,
  WORKSPACE_PREFERENCES_DEFAULT_COLUMN_PINNING,
  WORKSPACE_PREFERENCES_QUERY_PARAMS,
  WORKSPACE_PREFERENCES_DEFAULT_THREAD_TIMEOUT,
} from "./constants";
import WorkspacePreferencesActionsCell from "./WorkspacePreferencesActionsCell";
import EditThreadTimeoutDialog from "./EditThreadTimeoutDialog";
import useAppStore from "@/store/AppStore";

const WorkspacePreferencesTab: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: workspaceConfig, isPending } = useWorkspaceConfig({
    workspaceName: workspaceName,
  });

  const [editPreferenceOpen, setEditPreferenceOpen] = useQueryParam(
    WORKSPACE_PREFERENCES_QUERY_PARAMS.EDIT_PREFERENCE,
    WorkspacePreferenceParam,
    {
      updateType: "replaceIn",
    },
  );

  const threadTimeoutValue =
    workspaceConfig?.timeout_to_mark_thread_as_inactive ??
    WORKSPACE_PREFERENCES_DEFAULT_THREAD_TIMEOUT;

  const data = useMemo(
    () => [
      {
        name: "Thread timeout",
        value: formatIso8601Duration(threadTimeoutValue) ?? "Not set",
        type: WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
      },
    ],
    [threadTimeoutValue],
  );

  const getPreferencesDialogConfig = useCallback(
    (type: WORKSPACE_PREFERENCE_TYPE) => {
      const isOpen = editPreferenceOpen === type;
      const setOpen = (v: boolean) => setEditPreferenceOpen(v ? type : null);

      return {
        open: isOpen,
        setOpen,
      };
    },
    [editPreferenceOpen, setEditPreferenceOpen],
  );

  const handleEdit = useCallback(
    (row: WorkspacePreference) => {
      setEditPreferenceOpen(row.type);
    },
    [setEditPreferenceOpen],
  );

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<WorkspacePreference, WorkspacePreference>(
        WORKSPACE_PREFERENCES_DEFAULT_COLUMNS,
        {},
      ),
      generateActionsColumDef({
        cell: WorkspacePreferencesActionsCell,
        customMeta: {
          onEdit: handleEdit,
        },
      }),
    ];
  }, [handleEdit]);

  return (
    <>
      {isPending ? (
        <Loader />
      ) : (
        <DataTable
          columns={columns}
          data={data}
          columnPinning={WORKSPACE_PREFERENCES_DEFAULT_COLUMN_PINNING}
        />
      )}

      <EditThreadTimeoutDialog
        {...getPreferencesDialogConfig(
          WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
        )}
        defaultValue={threadTimeoutValue}
      />
    </>
  );
};

export default WorkspacePreferencesTab;
