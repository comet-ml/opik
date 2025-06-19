import { createEnumParam } from "use-query-params";

export type WorkspacePreference = {
  name: string;
  value: string;
  type: WORKSPACE_PREFERENCE_TYPE;
};

export enum WORKSPACE_PREFERENCE_TYPE {
  THREAD_TIMEOUT = "thread_timeout",
}

export const WorkspacePreferenceParam =
  createEnumParam<WORKSPACE_PREFERENCE_TYPE>([
    WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
  ]);
