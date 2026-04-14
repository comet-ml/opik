import React, { lazy } from "react";
import {
  createRootRoute,
  createRoute,
  createRouter,
  Navigate,
  Outlet,
  ScrollRestoration,
} from "@tanstack/react-router";

import WorkspaceGuard from "@/v2/layout/WorkspaceGuard/WorkspaceGuard";
import ExperimentsPageGuard from "@/v2/layout/ExperimentsPageGuard";
import DashboardsPageGuard from "@/v2/layout/DashboardsPageGuard";
import PlaygroundPageGuard from "@/v2/layout/PlaygroundPageGuard";
import DatasetsPageGuard from "@/v2/layout/DatasetsPageGuard";
import SMEPageLayout from "@/v2/layout/SMEPageLayout/SMEPageLayout";
import ExperimentsPage from "@/v2/pages/ExperimentsPage/ExperimentsPage";
import CompareExperimentsPage from "@/v2/pages/CompareExperimentsPage/CompareExperimentsPage";
import HomePage from "@/v2/pages/HomePage/HomePage";
import PartialPageLayout from "@/v2/layout/PartialPageLayout/PartialPageLayout";
import EmptyPageLayout from "@/v2/layout/EmptyPageLayout/EmptyPageLayout";
import ProjectPage from "@/v2/pages/ProjectPage/ProjectPage";
import ProjectsPage from "@/v2/pages/ProjectsPage/ProjectsPage";
import LogsPage from "@/v2/pages/LogsPage/LogsPage";
import WorkspacePage from "@/v2/pages/WorkspacePage/WorkspacePage";
import PromptsPage from "@/v2/pages/PromptsPage/PromptsPage";
import PromptPage from "@/v2/pages/PromptPage/PromptPage";
import RedirectProjects from "@/v2/redirect/RedirectProjects";
import RedirectDatasets from "@/v2/redirect/RedirectDatasets";
import { createV1RedirectRoutes } from "@/v2/redirect/v1RedirectConfig";
import PlaygroundPage from "@/v2/pages/PlaygroundPage/PlaygroundPage";
import useAppStore from "@/store/AppStore";
import ConfigurationPage from "@/v2/pages/ConfigurationPage/ConfigurationPage";
import NewQuickstart from "@/v2/pages/GetStartedPage/NewQuickstart";
import AutomationLogsPage from "@/v2/pages/AutomationLogsPage/AutomationLogsPage";
import OnlineEvaluationPage from "@/v2/pages/OnlineEvaluationPage/OnlineEvaluationPage";
import AnnotationQueuesPage from "@/v2/pages/AnnotationQueuesPage/AnnotationQueuesPage";
import AnnotationQueuePage from "@/v2/pages/AnnotationQueuePage/AnnotationQueuePage";
import AgentConfigurationPage from "@/v2/pages/AgentConfigurationPage/AgentConfigurationPage";
import AgentRunnerPage from "@/v2/pages/AgentRunnerPage/AgentRunnerPage";
import PairingPage from "@/v2/pages/PairingPage/PairingPage";
import OptimizationsPage from "@/v2/pages/OptimizationsPage/OptimizationsPage";
import OptimizationsNewPage from "@/v2/pages/OptimizationsPage/OptimizationsNewPage/OptimizationsNewPage";
import OptimizationPage from "@/v2/pages/OptimizationPage/OptimizationPage";
import OptimizationCompareRedirect from "@/v2/pages/OptimizationPage/OptimizationCompareRedirect";
import TrialPage from "@/v2/pages/TrialPage/TrialPage";
import AlertsRouteWrapper from "@/v2/pages/AlertsPage/AlertsRouteWrapper";
import AlertEditPageGuard from "@/v2/layout/AlertEditPageGuard/AlertEditPageGuard";
import DashboardPage from "@/v2/pages/DashboardPage/DashboardPage";
import DashboardsPage from "@/v2/pages/DashboardsPage/DashboardsPage";
import EvaluationSuitesPage from "@/v2/pages/EvaluationSuitesPage/EvaluationSuitesPage";
import EvaluationSuitePage from "@/v2/pages/EvaluationSuitePage/EvaluationSuitePage";
import EvaluationSuiteItemsPage from "@/v2/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsPage";
import ProjectHomePage from "@/v2/pages/ProjectHomePage/ProjectHomePage";
import TracesTabRedirect from "@/v2/redirect/TracesTabRedirect";
import ProjectDashboardsPage from "@/v2/pages/ProjectDashboardsPage/ProjectDashboardsPage";

const TanStackRouterDevtools =
  process.env.NODE_ENV === "production"
    ? () => null
    : React.lazy(() =>
        import("@tanstack/router-devtools").then((res) => ({
          default: res.TanStackRouterDevtools,
        })),
      );

const rootRoute = createRootRoute({
  component: () => (
    <>
      <ScrollRestoration />
      <Outlet />
      <TanStackRouterDevtools />
    </>
  ),
});

const workspaceGuardRoute = createRoute({
  id: "workspaceGuard",
  getParentRoute: () => rootRoute,
  component: WorkspaceGuard,
});

const workspaceGuardPartialLayoutRoute = createRoute({
  id: "workspaceGuardPartialLayout",
  getParentRoute: () => rootRoute,
  component: () => <WorkspaceGuard Layout={PartialPageLayout} />,
});

const workspaceGuardSMELayoutRoute = createRoute({
  id: "workspaceGuardSMELayout",
  getParentRoute: () => rootRoute,
  component: () => <WorkspaceGuard Layout={SMEPageLayout} />,
});

const workspaceGuardEmptyLayoutRoute = createRoute({
  id: "workspaceGuardEmptyLayout",
  getParentRoute: () => rootRoute,
  component: () => <WorkspaceGuard Layout={EmptyPageLayout} />,
});

// ----------- pairing (root-level, no layout)
const pairingRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/opik/pair/v1",
  component: PairingPage,
});

// ----------- base redirect
const baseRoute = createRoute({
  path: "/",
  getParentRoute: () => workspaceGuardRoute,
  component: () => (
    <Navigate
      to="/$workspaceName/home"
      params={{ workspaceName: useAppStore.getState().activeWorkspaceName }}
    />
  ),
});

// ----------- home (redirects to active project traces)
const homeRoute = createRoute({
  path: "/$workspaceName/home",
  getParentRoute: () => workspaceGuardRoute,
  component: HomePage,
});

const workspaceRoute = createRoute({
  path: "/$workspaceName",
  getParentRoute: () => workspaceGuardRoute,
  component: WorkspacePage,
});

// ----------- quickstart
const quickstartRoute = createRoute({
  path: "/$workspaceName/quickstart",
  getParentRoute: () => workspaceGuardPartialLayoutRoute,
  component: () => (
    <Navigate
      to="/$workspaceName/home"
      params={{ workspaceName: useAppStore.getState().activeWorkspaceName }}
    />
  ),
});

// ----------- get started
const getStartedRoute = createRoute({
  path: "/$workspaceName/get-started",
  getParentRoute: () => workspaceGuardPartialLayoutRoute,
  component: NewQuickstart,
  staticData: {
    hideUpgradeButton: true,
  },
});

// ═══════════════════════════════════════════════════════════════
// PROJECTS (workspace-level management + project-scoped routes)
// /$workspaceName/projects/...
// ═══════════════════════════════════════════════════════════════

const projectsRoute = createRoute({
  path: "/projects",
  getParentRoute: () => workspaceRoute,
});

const projectsListRoute = createRoute({
  path: "/",
  getParentRoute: () => projectsRoute,
  component: ProjectsPage,
  staticData: {
    title: "Projects",
  },
});

const projectScopedRoute = createRoute({
  path: "/$projectId",
  getParentRoute: () => projectsRoute,
  component: ProjectPage,
  staticData: {
    param: "projectId",
  },
});

// ----------- project home (project-scoped)
const projectHomeRoute = createRoute({
  path: "/home",
  getParentRoute: () => projectScopedRoute,
  component: ProjectHomePage,
});

// ----------- logs (project-scoped)
const logsRoute = createRoute({
  path: "/logs",
  getParentRoute: () => projectScopedRoute,
  component: LogsPage,
  staticData: {
    title: "Logs",
  },
});

// ----------- dashboards (project-scoped)
const projectDashboardsRoute = createRoute({
  path: "/dashboards",
  getParentRoute: () => projectScopedRoute,
  component: ProjectDashboardsPage,
  staticData: {
    title: "Dashboards",
  },
});

// ----------- traces redirect (old path → /logs, handles ?tab= params)
const tracesRedirectRoute = createRoute({
  path: "/traces",
  getParentRoute: () => projectScopedRoute,
  component: TracesTabRedirect,
});

// ----------- experiments (project-scoped)
const experimentsRoute = createRoute({
  path: "/experiments",
  getParentRoute: () => projectScopedRoute,
  component: ExperimentsPageGuard,
  staticData: {
    title: "Experiments",
  },
});

const experimentsListRoute = createRoute({
  path: "/",
  getParentRoute: () => experimentsRoute,
  component: ExperimentsPage,
});

const compareExperimentsRoute = createRoute({
  path: "/$datasetId/compare",
  getParentRoute: () => experimentsRoute,
  component: CompareExperimentsPage,
  staticData: {
    param: "compare",
    paramValue: "Compare",
  },
});

// ----------- evaluation suites (project-scoped)
const evaluationSuitesRoute = createRoute({
  path: "/evaluation-suites",
  getParentRoute: () => projectScopedRoute,
  component: DatasetsPageGuard,
  staticData: {
    title: "Evaluation suites",
  },
});

const evaluationSuitesListRoute = createRoute({
  path: "/",
  getParentRoute: () => evaluationSuitesRoute,
  component: EvaluationSuitesPage,
});

const evaluationSuiteRoute = createRoute({
  path: "/$suiteId",
  getParentRoute: () => evaluationSuitesRoute,
  component: EvaluationSuitePage,
  staticData: {
    param: "suiteId",
  },
});

const evaluationSuiteItemsRoute = createRoute({
  path: "/items",
  getParentRoute: () => evaluationSuiteRoute,
  component: EvaluationSuiteItemsPage,
});

// ----------- prompts (project-scoped)
const promptsRoute = createRoute({
  path: "/prompts",
  getParentRoute: () => projectScopedRoute,
  staticData: {
    title: "Prompt library",
  },
});

const promptsListRoute = createRoute({
  path: "/",
  getParentRoute: () => promptsRoute,
  component: PromptsPage,
});

const promptRoute = createRoute({
  path: "/$promptId",
  getParentRoute: () => promptsRoute,
  component: PromptPage,
  staticData: {
    param: "promptId",
  },
});

// ----------- playground (project-scoped)
const playgroundRoute = createRoute({
  path: "/playground",
  getParentRoute: () => projectScopedRoute,
  staticData: {
    title: "Playground",
  },
  component: PlaygroundPageGuard,
});

const playgroundIndexRoute = createRoute({
  path: "/",
  getParentRoute: () => playgroundRoute,
  component: PlaygroundPage,
});

// ----------- optimizations (project-scoped)
const optimizationsRoute = createRoute({
  path: "/optimizations",
  getParentRoute: () => projectScopedRoute,
  staticData: {
    title: "Optimization studio",
  },
});

const optimizationsListRoute = createRoute({
  path: "/",
  getParentRoute: () => optimizationsRoute,
  component: OptimizationsPage,
});

const optimizationsNewRoute = createRoute({
  path: "/new",
  getParentRoute: () => optimizationsRoute,
  component: OptimizationsNewPage,
  staticData: {
    param: "optimizationsNew",
    paramValue: "new",
  },
});

const optimizationCompareRedirectRoute = createRoute({
  path: "/$datasetId/compare",
  getParentRoute: () => optimizationsRoute,
  component: OptimizationCompareRedirect,
});

const optimizationBaseRoute = createRoute({
  path: "/$optimizationId",
  getParentRoute: () => optimizationsRoute,
  staticData: {
    param: "optimizationId",
  },
});

const optimizationRoute = createRoute({
  path: "/",
  getParentRoute: () => optimizationBaseRoute,
  component: OptimizationPage,
});

const trialRoute = createRoute({
  path: "/trials",
  getParentRoute: () => optimizationBaseRoute,
  component: TrialPage,
  staticData: {
    param: "trial",
    paramValue: "trials",
  },
});

// ----------- agent configuration (project-scoped)
const agentConfigurationRoute = createRoute({
  path: "/agent-configuration",
  getParentRoute: () => projectScopedRoute,
  staticData: {
    title: "Agent configuration",
  },
  component: AgentConfigurationPage,
});

// ----------- agent runner (project-scoped)
const agentRunnerRoute = createRoute({
  path: "/agent-runner",
  getParentRoute: () => projectScopedRoute,
  staticData: {
    title: "Agent sandbox",
  },
  component: AgentRunnerPage,
});

// ----------- online evaluation (project-scoped)
const onlineEvaluationRoute = createRoute({
  path: "/online-evaluation",
  getParentRoute: () => projectScopedRoute,
  staticData: {
    title: "Online evaluation",
  },
  component: OnlineEvaluationPage,
});

// ----------- annotation queues (project-scoped)
const annotationQueuesRoute = createRoute({
  path: "/annotation-queues",
  getParentRoute: () => projectScopedRoute,
  staticData: {
    title: "Annotation queues",
  },
});

const annotationQueuesListRoute = createRoute({
  path: "/",
  getParentRoute: () => annotationQueuesRoute,
  component: AnnotationQueuesPage,
});

const annotationQueueDetailsRoute = createRoute({
  path: "/$annotationQueueId",
  getParentRoute: () => annotationQueuesRoute,
  component: AnnotationQueuePage,
  staticData: {
    param: "annotationQueueId",
  },
});

// ----------- alerts (project-scoped)
const alertsRoute = createRoute({
  path: "/alerts",
  getParentRoute: () => projectScopedRoute,
  staticData: {
    title: "Alerts",
  },
  component: AlertsRouteWrapper,
});

const alertNewRoute = createRoute({
  path: "/new",
  getParentRoute: () => alertsRoute,
  staticData: {
    title: "New alert",
  },
  component: AlertEditPageGuard,
});

const alertEditRoute = createRoute({
  path: "/$alertId",
  getParentRoute: () => alertsRoute,
  staticData: {
    param: "alertId",
  },
  component: AlertEditPageGuard,
});

// ═══════════════════════════════════════════════════════════════
// WORKSPACE-LEVEL ROUTES (stay at workspace level)
// ═══════════════════════════════════════════════════════════════

// ----------- dashboards (workspace-level)
const dashboardsRoute = createRoute({
  path: "/dashboards",
  getParentRoute: () => workspaceRoute,
  component: DashboardsPageGuard,
  staticData: {
    title: "Dashboards",
  },
});

const dashboardsPageRoute = createRoute({
  path: "/",
  getParentRoute: () => dashboardsRoute,
  component: DashboardsPage,
});

const dashboardDetailRoute = createRoute({
  path: "/$dashboardId",
  getParentRoute: () => dashboardsRoute,
  component: DashboardPage,
  staticData: {
    param: "dashboardId",
  },
});

// ----------- configuration (workspace-level)
const configurationRoute = createRoute({
  path: "/configuration",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Configuration",
  },
  component: ConfigurationPage,
});

// ═══════════════════════════════════════════════════════════════
// SDK REDIRECT ROUTES
// ═══════════════════════════════════════════════════════════════

const redirectRoute = createRoute({
  path: "/redirect",
  getParentRoute: () => workspaceRoute,
});

const homeRedirectRoute = createRoute({
  path: "/",
  getParentRoute: () => redirectRoute,
  component: () => <Navigate to="/" />,
});

const redirectProjectsRoute = createRoute({
  path: "/projects",
  getParentRoute: () => redirectRoute,
  component: RedirectProjects,
});

const redirectDatasetsRoute = createRoute({
  path: "/datasets",
  getParentRoute: () => redirectRoute,
  component: RedirectDatasets,
});

// ----------- SME flow
const homeSMERoute = createRoute({
  path: "/$workspaceName/sme",
  getParentRoute: () => workspaceGuardSMELayoutRoute,
  component: lazy(() => import("@/v2/pages/SMEFlowPage/SMEFlowPage")),
});

// ----------- Automation logs
const automationLogsRoute = createRoute({
  path: "/$workspaceName/automation-logs",
  getParentRoute: () => workspaceGuardEmptyLayoutRoute,
  component: AutomationLogsPage,
});

// ═══════════════════════════════════════════════════════════════
// V1 COMPAT REDIRECTS
// ═══════════════════════════════════════════════════════════════

const v1RedirectRoutes = createV1RedirectRoutes(workspaceRoute);

// ═══════════════════════════════════════════════════════════════
// ROUTE TREE
// ═══════════════════════════════════════════════════════════════

const routeTree = rootRoute.addChildren([
  pairingRoute,
  workspaceGuardEmptyLayoutRoute.addChildren([automationLogsRoute]),
  workspaceGuardPartialLayoutRoute.addChildren([
    quickstartRoute,
    getStartedRoute,
  ]),
  workspaceGuardSMELayoutRoute.addChildren([homeSMERoute]),
  workspaceGuardRoute.addChildren([
    baseRoute,
    homeRoute,
    workspaceRoute.addChildren([
      // Projects: workspace-level list + project-scoped routes
      projectsRoute.addChildren([
        projectsListRoute,
        projectScopedRoute.addChildren([
          projectHomeRoute,
          logsRoute,
          projectDashboardsRoute,
          tracesRedirectRoute,
          experimentsRoute.addChildren([
            experimentsListRoute,
            compareExperimentsRoute,
          ]),
          evaluationSuitesRoute.addChildren([
            evaluationSuitesListRoute,
            evaluationSuiteRoute.addChildren([evaluationSuiteItemsRoute]),
          ]),
          promptsRoute.addChildren([promptsListRoute, promptRoute]),
          playgroundRoute.addChildren([playgroundIndexRoute]),
          optimizationsRoute.addChildren([
            optimizationsListRoute,
            optimizationsNewRoute,
            optimizationCompareRedirectRoute,
            optimizationBaseRoute.addChildren([optimizationRoute, trialRoute]),
          ]),
          agentConfigurationRoute,
          agentRunnerRoute,
          onlineEvaluationRoute,
          annotationQueuesRoute.addChildren([
            annotationQueuesListRoute,
            annotationQueueDetailsRoute,
          ]),
          alertsRoute.addChildren([alertNewRoute, alertEditRoute]),
        ]),
      ]),

      // Workspace-level routes
      dashboardsRoute.addChildren([dashboardsPageRoute, dashboardDetailRoute]),
      configurationRoute,

      // SDK redirects
      redirectRoute.addChildren([
        homeRedirectRoute,
        redirectProjectsRoute,
        redirectDatasetsRoute,
      ]),

      // V1 compat redirects (workspace-level → project-scoped)
      ...v1RedirectRoutes,
    ]),
  ]),
]);

export const router = createRouter({
  basepath: import.meta.env.VITE_BASE_URL || "/",
  routeTree,
});

export type AppRouter = typeof router;
