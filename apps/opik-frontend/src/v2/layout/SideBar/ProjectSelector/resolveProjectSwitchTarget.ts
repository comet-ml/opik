type RouteMatchLike = {
  pathname: string;
};

export type ProjectSwitchTarget = {
  to: string;
  params: Record<string, string>;
  search?: Record<string, unknown>;
};

const PROJECT_ROUTE_PREFIX = "/$workspaceName/projects/$projectId/";
const HOME_ROUTE = `${PROJECT_ROUTE_PREFIX}home`;

const COMMON_UI_STATE = ["size", "sortedColumns", "columns"] as const;

const PORTABLE_SEARCH_PARAMS: Record<string, readonly string[]> = {
  home: [],
  logs: [...COMMON_UI_STATE, "logsType", "traceTab"],
  dashboards: [],
  experiments: [...COMMON_UI_STATE],
  datasets: [...COMMON_UI_STATE],
  "test-suites": [...COMMON_UI_STATE],
  playground: [],
  optimizations: [...COMMON_UI_STATE],
  "agent-configuration": [],
  "agent-playground": [],
  "online-evaluation": [...COMMON_UI_STATE],
  "annotation-queues": [...COMMON_UI_STATE],
  alerts: [...COMMON_UI_STATE],
};

export function resolveProjectSwitchTarget(
  matches: readonly RouteMatchLike[],
  currentSearch: Record<string, unknown>,
  workspaceName: string,
  newProjectId: string,
): ProjectSwitchTarget {
  const homeTarget: ProjectSwitchTarget = {
    to: HOME_ROUTE,
    params: { workspaceName, projectId: newProjectId },
  };

  const deepest = matches[matches.length - 1];
  if (!deepest) return homeTarget;

  const projectsMarker = "/projects/";
  const projectsIdx = deepest.pathname.indexOf(projectsMarker);
  if (projectsIdx < 0) return homeTarget;

  const afterProjects = deepest.pathname.slice(
    projectsIdx + projectsMarker.length,
  );
  const slashAfterId = afterProjects.indexOf("/");
  if (slashAfterId < 0) return homeTarget;

  const afterProjectId = afterProjects.slice(slashAfterId + 1);
  const section = afterProjectId.split("/")[0];
  if (!section || !(section in PORTABLE_SEARCH_PARAMS)) return homeTarget;

  const allowed = PORTABLE_SEARCH_PARAMS[section];
  const filteredSearch = Object.fromEntries(
    Object.entries(currentSearch).filter(([key]) => allowed.includes(key)),
  );

  return {
    to: `${PROJECT_ROUTE_PREFIX}${section}`,
    params: { workspaceName, projectId: newProjectId },
    search: filteredSearch,
  };
}
