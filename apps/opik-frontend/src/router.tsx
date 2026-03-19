import React, { lazy } from "react";
import {
  createRootRoute,
  createRoute,
  createRouter,
  Navigate,
  Outlet,
  ScrollRestoration,
} from "@tanstack/react-router";

import WorkspaceGuard from "@/v1/layout/WorkspaceGuard/WorkspaceGuard";
import ExperimentsPageGuard from "@/v1/layout/ExperimentsPageGuard";
import DatasetsPageGuard from "@/v1/layout/DatasetsPageGuard";
import DashboardsPageGuard from "@/v1/layout/DashboardsPageGuard";
import SMEPageLayout from "@/v1/layout/SMEPageLayout/SMEPageLayout";
import ExperimentsPage from "@/v1/pages/ExperimentsPage/ExperimentsPage";
import CompareExperimentsPage from "@/v1/pages/CompareExperimentsPage/CompareExperimentsPage";
import HomePage from "@/v1/pages/HomePage/HomePage";
import OldHomePage from "@/v1/pages/HomePage/OldHomePage";
import PartialPageLayout from "@/v1/layout/PartialPageLayout/PartialPageLayout";
import EmptyPageLayout from "@/v1/layout/EmptyPageLayout/EmptyPageLayout";
import ProjectPage from "@/v1/pages/ProjectPage/ProjectPage";
import ProjectsPage from "@/v1/pages/ProjectsPage/ProjectsPage";
import TracesPage from "@/v1/pages/TracesPage/TracesPage";
import WorkspacePage from "@/v1/pages/WorkspacePage/WorkspacePage";
import PromptsPage from "@/v1/pages/PromptsPage/PromptsPage";
import PromptPage from "@/v1/pages/PromptPage/PromptPage";
import RedirectProjects from "@/v1/redirect/RedirectProjects";
import RedirectDatasets from "@/v1/redirect/RedirectDatasets";
import PlaygroundPage from "@/v1/pages/PlaygroundPage/PlaygroundPage";
import useAppStore from "@/store/AppStore";
import ConfigurationPage from "@/v1/pages/ConfigurationPage/ConfigurationPage";
import GetStartedPage from "@/v1/pages/GetStartedPage/GetStartedPage";
import AutomationLogsPage from "@/v1/pages/AutomationLogsPage/AutomationLogsPage";
import OnlineEvaluationPage from "@/v1/pages/OnlineEvaluationPage/OnlineEvaluationPage";
import AnnotationQueuesPage from "@/v1/pages/AnnotationQueuesPage/AnnotationQueuesPage";
import AnnotationQueuePage from "@/v1/pages/AnnotationQueuePage/AnnotationQueuePage";
import OptimizationsPage from "@/v1/pages/OptimizationsPage/OptimizationsPage";
import OptimizationsNewPage from "@/v1/pages/OptimizationsPage/OptimizationsNewPage/OptimizationsNewPage";
import OptimizationPage from "@/v1/pages/OptimizationPage/OptimizationPage";
import OptimizationCompareRedirect from "@/v1/pages/OptimizationPage/OptimizationCompareRedirect";
import TrialPage from "@/v1/pages/TrialPage/TrialPage";
import AlertsRouteWrapper from "@/v1/pages/AlertsPage/AlertsRouteWrapper";
import AlertEditPageGuard from "@/v1/layout/AlertEditPageGuard/AlertEditPageGuard";
import DashboardPage from "@/v1/pages/DashboardPage/DashboardPage";
import DashboardsPage from "@/v1/pages/DashboardsPage/DashboardsPage";
import EvaluationSuitesPage from "@/v1/pages/EvaluationSuitesPage/EvaluationSuitesPage";
import EvaluationSuitePage from "@/v1/pages/EvaluationSuitePage/EvaluationSuitePage";
import EvaluationSuiteItemsPage from "@/v1/pages/EvaluationSuiteItemsPage/EvaluationSuiteItemsPage";

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
  component: GetStartedPage,
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

// ----------- dashboards
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
    title: "Compare",
    param: "compare",
    paramValue: "compare",
  },
});

// Optimization studio
const optimizationsRoute = createRoute({
  path: "/optimizations",
  getParentRoute: () => workspaceRoute,
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

// ----------- evaluation suites
const evaluationSuitesRoute = createRoute({
  path: "/evaluation-suites",
  getParentRoute: () => workspaceRoute,
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

// ----------- datasets (legacy redirects)
const datasetsRoute = createRoute({
  path: "/datasets",
  component: DatasetsPageGuard,
  getParentRoute: () => workspaceRoute,
});

const datasetsListRoute = createRoute({
  path: "/",
  getParentRoute: () => datasetsRoute,
  component: () => (
    <Navigate
      to="/$workspaceName/evaluation-suites"
      params={{ workspaceName: useAppStore.getState().activeWorkspaceName }}
    />
  ),
});

const datasetRoute = createRoute({
  path: "/$datasetId",
  getParentRoute: () => datasetsRoute,
});

const datasetRedirectRoute = createRoute({
  path: "/",
  getParentRoute: () => datasetRoute,
  component: () => {
    const { datasetId } = datasetRoute.useParams();
    return (
      <Navigate
        to="/$workspaceName/evaluation-suites/$suiteId"
        params={{
          workspaceName: useAppStore.getState().activeWorkspaceName,
          suiteId: datasetId,
        }}
      />
    );
  },
});

const datasetItemsRoute = createRoute({
  path: "/items",
  getParentRoute: () => datasetRoute,
  component: () => {
    const { datasetId } = datasetRoute.useParams();
    return (
      <Navigate
        to="/$workspaceName/evaluation-suites/$suiteId/items"
        params={{
          workspaceName: useAppStore.getState().activeWorkspaceName,
          suiteId: datasetId,
        }}
      />
    );
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
  component: lazy(() => import("@/v1/pages/SMEFlowPage/SMEFlowPage")),
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
  getParentRoute: () => workspaceRoute,
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
    workspaceRoute.addChildren([
      dashboardsRoute.addChildren([dashboardsPageRoute, dashboardDetailRoute]),
      projectsRoute.addChildren([
        projectsListRoute,
        projectRoute.addChildren([tracesRoute]),
      ]),
      experimentsRoute.addChildren([
        experimentsListRoute,
        compareExperimentsRoute,
      ]),
      evaluationSuitesRoute.addChildren([
        evaluationSuitesListRoute,
        evaluationSuiteRoute.addChildren([evaluationSuiteItemsRoute]),
      ]),
      optimizationsRoute.addChildren([
        optimizationsListRoute,
        optimizationsNewRoute,
        optimizationCompareRedirectRoute,
        optimizationBaseRoute.addChildren([optimizationRoute, trialRoute]),
      ]),
      datasetsRoute.addChildren([
        datasetsListRoute,
        datasetRoute.addChildren([datasetRedirectRoute, datasetItemsRoute]),
      ]),
      promptsRoute.addChildren([promptsListRoute, promptRoute]),
      redirectRoute.addChildren([
        homeRedirectRoute,
        redirectProjectsRoute,
        redirectDatasetsRoute,
      ]),
      playgroundRoute,
      configurationRoute,
      alertsRoute.addChildren([alertNewRoute, alertEditRoute]),
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
