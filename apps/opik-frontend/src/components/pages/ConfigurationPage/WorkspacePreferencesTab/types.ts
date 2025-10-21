import { createEnumParam } from "use-query-params";

export type WorkspacePreference = {
  name: string;
  value: string;
  type: WORKSPACE_PREFERENCE_TYPE;
};

export enum WORKSPACE_PREFERENCE_TYPE {
  THREAD_TIMEOUT = "thread_timeout",
  TRUNCATION_TOGGLE = "truncation_toggle",
}

export const WorkspacePreferenceParam =
  createEnumParam<WORKSPACE_PREFERENCE_TYPE>([
    WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
    WORKSPACE_PREFERENCE_TYPE.TRUNCATION_TOGGLE,
  ]);
