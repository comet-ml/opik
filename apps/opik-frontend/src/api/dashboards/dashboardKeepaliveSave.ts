import noop from "lodash/noop";

import api, { BASE_API_URL, DASHBOARDS_REST_ENDPOINT } from "@/api/api";
import { DashboardState } from "@/types/dashboard";

export const keepaliveSaveDashboard = (
  dashboardId: string,
  config: DashboardState,
): void => {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };

  const workspace = api.defaults.headers.common["Comet-Workspace"];
  if (typeof workspace === "string") {
    headers["Comet-Workspace"] = workspace;
  }

  fetch(`${BASE_API_URL}${DASHBOARDS_REST_ENDPOINT}${dashboardId}`, {
    method: "PATCH",
    headers,
    body: JSON.stringify({ id: dashboardId, config }),
    credentials: "include",
    keepalive: true,
  }).catch(noop);
};
