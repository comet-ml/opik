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
import PlaygroundPageGuard from "@/v1/layout/PlaygroundPageGuard";
import OptimizationStudioPageGuard from "@/v1/layout/OptimizationStudioPageGuard";
import OptimizationsPageGuard from "@/v1/layout/OptimizationsPageGuard";
import SMEPageLayout from "@/v1/layout/SMEPageLayout/SMEPageLayout";
import ExperimentsPage from "@/v1/pages/ExperimentsPage/ExperimentsPage";
const CompareExperimentsPage = lazy(
  () => import("@/v1/pages/CompareExperimentsPage/CompareExperimentsPage"),
);
import HomePage from "@/v1/pages/HomePage/HomePage";
import OldHomePage from "@/v1/pages/HomePage/OldHomePage";
import PartialPageLayout from "@/v1/layout/PartialPageLayout/PartialPageLayout";
import EmptyPageLayout from "@/v1/layout/EmptyPageLayout/EmptyPageLayout";
import ProjectPage from "@/v1/pages/ProjectPage/ProjectPage";
import ProjectsPage from "@/v1/pages/ProjectsPage/ProjectsPage";
import TracesPage from "@/v1/pages/TracesPage/TracesPage";
import WorkspacePage from "@/v1/pages/WorkspacePage/WorkspacePage";
import PromptsPage from "@/v1/pages/PromptsPage/PromptsPage";
const PromptPage = lazy(() => import("@/v1/pages/PromptPage/PromptPage"));
import RedirectProjects from "@/v1/redirect/RedirectProjects";
import RedirectDatasets from "@/v1/redirect/RedirectDatasets";
const PlaygroundPage = lazy(
  () => import("@/v1/pages/PlaygroundPage/PlaygroundPage"),
);
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
const AlertsRouteWrapper = lazy(
  () => import("@/v1/pages/AlertsPage/AlertsRouteWrapper"),
);
import AlertEditPageGuard from "@/v1/layout/AlertEditPageGuard/AlertEditPageGuard";
import DashboardPage from "@/v1/pages/DashboardPage/DashboardPage";
import DashboardsPage from "@/v1/pages/DashboardsPage/DashboardsPage";
import TestSuitesPage from "@/v1/pages/TestSuitesPage/TestSuitesPage";
import TestSuitePage from "@/v1/pages/TestSuitePage/TestSuitePage";
import TestSuiteItemsPage from "@/v1/pages/TestSuiteItemsPage/TestSuiteItemsPage";
import PairV1Page from "@/v1/pages/PairV1Page/PairV1Page";
import PairRouteVersionGuard from "@/shared/WorkspaceVersionResolver/PairRouteVersionGuard";

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

// ----------- pairing (root-level, no layout — V1 shows upgrade-required screen)
// TanStack strips the router basepath before matching, so the canonical
// route is basepath-relative: "/pair/v1" covers cloud (basepath "/opik"
// → URL "/opik/pair/v1" strips to "/pair/v1").
//
// The legacy "/opik/pair/v1" alias below is an OSS-only fallback: the
// Python SDK hardcodes "{origin}/opik/pair/v1" regardless of deployment
// (see sdks/python/src/opik/cli/pairing.py), so on OSS (basepath "/") the
// "/opik/" prefix isn't stripped and the router sees "/opik/pair/v1".
// TODO: make the Python SDK build basepath-aware pairing URLs and drop
// this alias once shipped CLI versions roll over.
const PairRouteComponent = () => (
  <PairRouteVersionGuard>
    <PairV1Page />
  </PairRouteVersionGuard>
);
const pairingRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/pair/v1",
  component: PairRouteComponent,
});
const pairingRouteOssAlias = createRoute({
  getParentRoute: () => rootRoute,
  path: "/opik/pair/v1",
  component: PairRouteComponent,
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
    param: "compare",
    paramValue: "Compare",
  },
});

// Optimization studio
const optimizationsRoute = createRoute({
  path: "/optimizations",
  getParentRoute: () => workspaceRoute,
  component: OptimizationsPageGuard,
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
  component: OptimizationStudioPageGuard,
  staticData: {
    param: "optimizationsNew",
    paramValue: "new",
  },
});

const optimizationsNewIndexRoute = createRoute({
  path: "/",
  getParentRoute: () => optimizationsNewRoute,
  component: OptimizationsNewPage,
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

// ----------- test suites
const testSuitesRoute = createRoute({
  path: "/test-suites",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Datasets",
  },
});

const testSuitesListRoute = createRoute({
  path: "/",
  getParentRoute: () => testSuitesRoute,
  component: TestSuitesPage,
});

const testSuiteRoute = createRoute({
  path: "/$suiteId",
  getParentRoute: () => testSuitesRoute,
  component: TestSuitePage,
  staticData: {
    param: "suiteId",
  },
});

const testSuiteItemsRoute = createRoute({
  path: "/items",
  getParentRoute: () => testSuiteRoute,
  component: TestSuiteItemsPage,
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
      to="/$workspaceName/test-suites"
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
        to="/$workspaceName/test-suites/$suiteId"
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
        to="/$workspaceName/test-suites/$suiteId/items"
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
  component: PlaygroundPageGuard,
});

const playgroundIndexRoute = createRoute({
  path: "/",
  getParentRoute: () => playgroundRoute,
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
  pairingRoute,
  pairingRouteOssAlias,
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
      testSuitesRoute.addChildren([
        testSuitesListRoute,
        testSuiteRoute.addChildren([testSuiteItemsRoute]),
      ]),
      optimizationsRoute.addChildren([
        optimizationsListRoute,
        optimizationsNewRoute.addChildren([optimizationsNewIndexRoute]),
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
      playgroundRoute.addChildren([playgroundIndexRoute]),
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

export type AppRouter = typeof router;
