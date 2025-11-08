import { ColumnPinningState } from "@tanstack/react-table";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { WorkspacePreference } from "./types";

export const WORKSPACE_PREFERENCES_DEFAULT_THREAD_TIMEOUT = "PT15M";
export const WORKSPACE_PREFERENCES_DEFAULT_TRUNCATION_TOGGLE = true;

export const WORKSPACE_PREFERENCES_DEFAULT_COLUMNS: ColumnData<WorkspacePreference>[] =
  [
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

export const WORKSPACE_PREFERENCES_DEFAULT_COLUMN_PINNING: ColumnPinningState =
  {
    left: [COLUMN_NAME_ID],
    right: [],
  };

export const WORKSPACE_PREFERENCES_QUERY_PARAMS = {
  EDIT_PREFERENCE: "editPreference",
} as const;
