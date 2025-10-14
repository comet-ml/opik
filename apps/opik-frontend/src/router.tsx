import React, { lazy } from "react";
import {
  createRootRoute,
  createRoute,
  createRouter,
  Navigate,
  Outlet,
  ScrollRestoration,
} from "@tanstack/react-router";

import WorkspaceGuard from "@/components/layout/WorkspaceGuard/WorkspaceGuard";
import SMEPageLayout from "@/components/layout/SMEPageLayout/SMEPageLayout";
import DatasetItemsPage from "@/components/pages/DatasetItemsPage/DatasetItemsPage";
import DatasetPage from "@/components/pages/DatasetPage/DatasetPage";
import DatasetsPage from "@/components/pages/DatasetsPage/DatasetsPage";
import ExperimentsPage from "@/components/pages/ExperimentsPage/ExperimentsPage";
import CompareExperimentsPage from "@/components/pages/CompareExperimentsPage/CompareExperimentsPage";
import HomePage from "@/components/pages/HomePage/HomePage";
import OldHomePage from "@/components/pages/HomePage/OldHomePage";
import ChatPage from "@/components/pages/ChatPage/ChatPage";
import PartialPageLayout from "@/components/layout/PartialPageLayout/PartialPageLayout";
import EmptyPageLayout from "@/components/layout/EmptyPageLayout/EmptyPageLayout";
import ProjectPage from "@/components/pages/ProjectPage/ProjectPage";
import ProjectsPage from "@/components/pages/ProjectsPage/ProjectsPage";
import TracesPage from "@/components/pages/TracesPage/TracesPage";
import WorkspacePage from "@/components/pages/WorkspacePage/WorkspacePage";
import PromptsPage from "@/components/pages/PromptsPage/PromptsPage";
import PromptPage from "@/components/pages/PromptPage/PromptPage";
import RedirectProjects from "@/components/redirect/RedirectProjects";
import RedirectDatasets from "@/components/redirect/RedirectDatasets";
import PlaygroundPage from "@/components/pages/PlaygroundPage/PlaygroundPage";
import useAppStore from "@/store/AppStore";
import ConfigurationPage from "@/components/pages/ConfigurationPage/ConfigurationPage";
import NewQuickstartPage from "@/components/pages/NewQuickstartPage/NewQuickstartPage";
import AutomationLogsPage from "@/components/pages/AutomationLogsPage/AutomationLogsPage";
import OnlineEvaluationPage from "@/components/pages/OnlineEvaluationPage/OnlineEvaluationPage";
import AnnotationQueuesPage from "@/components/pages/AnnotationQueuesPage/AnnotationQueuesPage";
import AnnotationQueuePage from "@/components/pages/AnnotationQueuePage/AnnotationQueuePage";
import OptimizationsPage from "@/components/pages/OptimizationsPage/OptimizationsPage";
import OptimizationPage from "@/components/pages/OptimizationPage/OptimizationPage";
import CompareOptimizationsPage from "@/components/pages/CompareOptimizationsPage/CompareOptimizationsPage";
import CompareTrialsPage from "@/components/pages/CompareTrialsPage/CompareTrialsPage";
import AddEditAlertPage from "@/components/pages/ConfigurationPage/AlertsTab/AddEditAlertPage/AddEditAlertPage";
import AlertNestedRoute from "@/components/pages/ConfigurationPage/AlertsTab/AddEditAlertPage/AlertNestedRoute";

declare module "@tanstack/react-router" {
  interface StaticDataRouteOption {
    hideUpgradeButton?: boolean;
    title?: string;
    param?: string;
    paramValue?: string;
  }
}

const TanStackRouterDevtools =
  process.env.NODE_ENV === "production"
    ? () => null // Render nothing in production
    : React.lazy(() =>
        // Lazy load in development
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
  component: NewQuickstartPage,
  staticData: {
    hideUpgradeButton: true,
  },
});

// ----------- home
// TODO temporary revert of old implementation, should be removed in the future
const homeRoute = createRoute({
  path: "/$workspaceName/home",
  getParentRoute: () => workspaceGuardRoute,
  component: OldHomePage,
  staticData: {
    title: "Home",
  },
});

const homeRouteNew = createRoute({
  path: "/$workspaceName/home-new",
  getParentRoute: () => workspaceGuardRoute,
  component: HomePage,
  staticData: {
    title: "Home",
  },
});

// ----------- chat
const chatRoute = createRoute({
  path: "/$workspaceName/chat",
  getParentRoute: () => workspaceGuardRoute,
  component: ChatPage,
  staticData: {
    title: "Chat",
  },
});

// ----------- projects
const projectsRoute = createRoute({
  path: "/projects",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Projects",
  },
});

const projectsListRoute = createRoute({
  path: "/",
  getParentRoute: () => projectsRoute,
  component: ProjectsPage,
});

const projectRoute = createRoute({
  path: "/$projectId",
  getParentRoute: () => projectsRoute,
  component: ProjectPage,
  staticData: {
    param: "projectId",
  },
});

const tracesRoute = createRoute({
  path: "/traces",
  getParentRoute: () => projectRoute,
  component: TracesPage,
});

// ----------- experiments
const experimentsRoute = createRoute({
  path: "/experiments",
  getParentRoute: () => workspaceRoute,
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
    paramValue: "compare",
  },
});

// Optimization runs
const optimizationsRoute = createRoute({
  path: "/optimizations",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Optimization runs",
  },
});

const optimizationsListRoute = createRoute({
  path: "/",
  getParentRoute: () => optimizationsRoute,
  component: OptimizationsPage,
});

const compareOptimizationsRoute = createRoute({
  path: "/$datasetId/compare",
  getParentRoute: () => optimizationsRoute,
  component: CompareOptimizationsPage,
  staticData: {
    param: "optimizationsCompare",
    paramValue: "optimizationsCompare",
  },
});

const optimizationBaseRoute = createRoute({
  path: "/$datasetId/$optimizationId",
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

const compareTrialsRoute = createRoute({
  path: "/compare",
  getParentRoute: () => optimizationBaseRoute,
  component: CompareTrialsPage,
  staticData: {
    param: "trialsCompare",
    paramValue: "trialsCompare",
  },
});

// ----------- datasets
const datasetsRoute = createRoute({
  path: "/datasets",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Datasets",
  },
});

const datasetsListRoute = createRoute({
  path: "/",
  getParentRoute: () => datasetsRoute,
  component: DatasetsPage,
});

const datasetRoute = createRoute({
  path: "/$datasetId",
  getParentRoute: () => datasetsRoute,
  component: DatasetPage,
  staticData: {
    param: "datasetId",
  },
});

const datasetItemsRoute = createRoute({
  path: "/items",
  getParentRoute: () => datasetRoute,
  component: DatasetItemsPage,
  staticData: {
    title: "Items",
  },
});

// ----------- prompts
const promptsRoute = createRoute({
  path: "/prompts",
  getParentRoute: () => workspaceRoute,
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

// ----------- redirect
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
  component: lazy(() => import("@/components/pages/SMEFlowPage/SMEFlowPage")),
});

// --------- playground

const playgroundRoute = createRoute({
  path: "/playground",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Playground",
  },
  component: PlaygroundPage,
});

// --------- configuration

const configurationRoute = createRoute({
  path: "/configuration",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Configuration",
  },
  component: ConfigurationPage,
});

const alertsRoute = createRoute({
  path: "/alerts",
  getParentRoute: () => configurationRoute,
  staticData: {
    title: "Alerts",
  },
  component: AlertNestedRoute,
});

const alertNewRoute = createRoute({
  path: "/new",
  getParentRoute: () => alertsRoute,
  staticData: {
    title: "New alert",
  },
  component: AddEditAlertPage,
});

const alertEditRoute = createRoute({
  path: "/$alertId",
  getParentRoute: () => alertsRoute,
  staticData: {
    param: "alertId",
  },
  component: AddEditAlertPage,
});

// --------- production

const onlineEvaluationRoute = createRoute({
  path: "/online-evaluation",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Online evaluation",
  },
  component: OnlineEvaluationPage,
});

const annotationQueuesRoute = createRoute({
  path: "/annotation-queues",
  getParentRoute: () => workspaceRoute,
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

// ----------- Automation logs

const automationLogsRoute = createRoute({
  path: "/$workspaceName/automation-logs",
  getParentRoute: () => workspaceGuardEmptyLayoutRoute,
  component: AutomationLogsPage,
});

const routeTree = rootRoute.addChildren([
  workspaceGuardEmptyLayoutRoute.addChildren([automationLogsRoute]),
  workspaceGuardPartialLayoutRoute.addChildren([
    quickstartRoute,
    getStartedRoute,
  ]),
  workspaceGuardSMELayoutRoute.addChildren([homeSMERoute]),
  workspaceGuardRoute.addChildren([
    baseRoute,
    homeRoute,
    homeRouteNew,
    chatRoute,
    workspaceRoute.addChildren([
      projectsRoute.addChildren([
        projectsListRoute,
        projectRoute.addChildren([tracesRoute]),
      ]),
      experimentsRoute.addChildren([
        experimentsListRoute,
        compareExperimentsRoute,
      ]),
      optimizationsRoute.addChildren([
        optimizationsListRoute,
        compareOptimizationsRoute,
        optimizationBaseRoute.addChildren([
          optimizationRoute,
          compareTrialsRoute,
        ]),
      ]),
      datasetsRoute.addChildren([
        datasetsListRoute,
        datasetRoute.addChildren([datasetItemsRoute]),
      ]),
      promptsRoute.addChildren([promptsListRoute, promptRoute]),
      redirectRoute.addChildren([
        homeRedirectRoute,
        redirectProjectsRoute,
        redirectDatasetsRoute,
      ]),
      playgroundRoute,
      configurationRoute.addChildren([
        alertsRoute.addChildren([alertNewRoute, alertEditRoute]),
      ]),
      onlineEvaluationRoute,
      annotationQueuesRoute.addChildren([
        annotationQueuesListRoute,
        annotationQueueDetailsRoute,
      ]),
    ]),
  ]),
]);

export const router = createRouter({
  basepath: import.meta.env.VITE_BASE_URL || "/",
  routeTree,
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
