import { useMemo, useCallback } from "react";
import { useQueryParam } from "use-query-params";

import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/components/shared/DataTable/DataTable";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import Loader from "@/components/shared/Loader/Loader";
import useWorkspaceConfig from "@/api/workspaces/useWorkspaceConfig";
import useWorkspaceConfigMutation from "@/api/workspaces/useWorkspaceConfigMutation";
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
  WORKSPACE_PREFERENCES_DEFAULT_TRUNCATION_TOGGLE,
} from "./constants";
import WorkspacePreferencesActionsCell from "./WorkspacePreferencesActionsCell";
import EditThreadTimeoutDialog from "./EditThreadTimeoutDialog";
import { EditThreadTimeoutFormValues } from "./EditThreadTimeoutForm";
import EditTruncationToggleDialog from "./EditTruncationToggleDialog";
import EditColorMapDialog from "./EditColorMapDialog";
import useAppStore from "@/store/AppStore";

const WorkspacePreferencesTab: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: workspaceConfig, isPending } = useWorkspaceConfig({
    workspaceName: workspaceName,
  });
  const { mutate: updateWorkspaceConfig } = useWorkspaceConfigMutation();

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

  const truncationToggleValue =
    workspaceConfig?.truncation_on_tables ??
    WORKSPACE_PREFERENCES_DEFAULT_TRUNCATION_TOGGLE;

  const colorMapCount = workspaceConfig?.color_map
    ? Object.keys(workspaceConfig.color_map).length
    : 0;

  const data = useMemo(
    () => [
      {
        name: "Thread online scoring rule cooldown period",
        value: formatIso8601Duration(threadTimeoutValue) ?? "Not set",
        type: WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
      },
      {
        name: "Data truncation in tables",
        value: truncationToggleValue ? "Enabled" : "Disabled",
        type: WORKSPACE_PREFERENCE_TYPE.TRUNCATION_TOGGLE,
      },
      {
        name: "Color mappings",
        value:
          colorMapCount > 0
            ? `${colorMapCount} mapping${colorMapCount !== 1 ? "s" : ""}`
            : "Not configured",
        type: WORKSPACE_PREFERENCE_TYPE.COLOR_MAP,
      },
    ],
    [threadTimeoutValue, truncationToggleValue, colorMapCount],
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

  const mergeConfigUpdate = useCallback(
    (
      updates: Partial<{
        timeout_to_mark_thread_as_inactive: string | null;
        truncation_on_tables: boolean;
        color_map: Record<string, string>;
      }>,
    ) => {
      updateWorkspaceConfig({
        config: {
          timeout_to_mark_thread_as_inactive:
            updates.timeout_to_mark_thread_as_inactive !== undefined
              ? updates.timeout_to_mark_thread_as_inactive
              : workspaceConfig?.timeout_to_mark_thread_as_inactive ?? null,
          truncation_on_tables:
            updates.truncation_on_tables !== undefined
              ? updates.truncation_on_tables
              : workspaceConfig?.truncation_on_tables ?? null,
          color_map:
            updates.color_map !== undefined
              ? updates.color_map
              : workspaceConfig?.color_map ?? null,
        },
      });
    },
    [
      updateWorkspaceConfig,
      workspaceConfig?.timeout_to_mark_thread_as_inactive,
      workspaceConfig?.truncation_on_tables,
      workspaceConfig?.color_map,
    ],
  );

  const handleThreadTimeoutSubmit = useCallback(
    (values: EditThreadTimeoutFormValues) => {
      mergeConfigUpdate({
        timeout_to_mark_thread_as_inactive:
          values.timeout_to_mark_thread_as_inactive,
      });
    },
    [mergeConfigUpdate],
  );

  const handleTruncationToggleSubmit = useCallback(
    (enabled: boolean) => {
      mergeConfigUpdate({
        truncation_on_tables: enabled,
      });
    },
    [mergeConfigUpdate],
  );

  const handleColorMapUpdate = useCallback(
    (colorMap: Record<string, string>) => {
      mergeConfigUpdate({ color_map: colorMap });
    },
    [mergeConfigUpdate],
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
        onSubmit={handleThreadTimeoutSubmit}
      />

      <EditTruncationToggleDialog
        {...getPreferencesDialogConfig(
          WORKSPACE_PREFERENCE_TYPE.TRUNCATION_TOGGLE,
        )}
        currentValue={truncationToggleValue}
        onConfirm={handleTruncationToggleSubmit}
      />

      <EditColorMapDialog
        {...getPreferencesDialogConfig(WORKSPACE_PREFERENCE_TYPE.COLOR_MAP)}
        colorMap={workspaceConfig?.color_map ?? null}
        onUpdate={handleColorMapUpdate}
      />
    </>
  );
};

export default WorkspacePreferencesTab;
