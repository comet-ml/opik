import React from "react";
import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  ScrollRestoration,
} from "@tanstack/react-router";

import WorkspaceGuard from "@/components/layout/WorkspaceGuard/WorkspaceGuard";
import DatasetItemsPage from "@/components/pages/DatasetItemsPage/DatasetItemsPage";
import DatasetPage from "@/components/pages/DatasetPage/DatasetPage";
import DatasetsPage from "@/components/pages/DatasetsPage/DatasetsPage";
import ExperimentsPage from "@/components/pages/ExperimentsPage/ExperimentsPage";
import CompareExperimentsPage from "@/components/pages/CompareExperimentsPage/CompareExperimentsPage";
import FeedbackDefinitionsPage from "@/components/pages/FeedbackDefinitionsPage/FeedbackDefinitionsPage";
import GetStartedPage from "@/components/pages/GetStartedPage/GetStartedPage";
import QuickstartPage from "@/components/pages/QuickstartPage/QuickstartPage";
import HomePage from "@/components/pages/HomePage/HomePage";
import PartialPageLayout from "@/components/layout/PartialPageLayout/PartialPageLayout";
import ProjectPage from "@/components/pages/ProjectPage/ProjectPage";
import ProjectsPage from "@/components/pages/ProjectsPage/ProjectsPage";
import TracesPage from "@/components/pages/TracesPage/TracesPage";
import WorkspacePage from "@/components/pages/WorkspacePage/WorkspacePage";
import PromptsPage from "@/components/pages/PromptsPage/PromptsPage";
import PromptPage from "@/components/pages/PromptPage/PromptPage";
import RedirectProjects from "@/components/redirect/RedirectProjects";

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

const homeRoute = createRoute({
  path: "/",
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
  component: QuickstartPage,
});

// ----------- get started
const getStartedRoute = createRoute({
  path: "/$workspaceName/get-started",
  getParentRoute: () => workspaceGuardPartialLayoutRoute,
  component: GetStartedPage,
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

// ----------- feedback definitions
const feedbackDefinitionsRoute = createRoute({
  path: "/feedback-definitions",
  getParentRoute: () => workspaceRoute,
  staticData: {
    title: "Feedback",
  },
});

const feedbackDefinitionsListRoute = createRoute({
  path: "/",
  getParentRoute: () => feedbackDefinitionsRoute,
  component: FeedbackDefinitionsPage,
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

const redirectProjectRoute = createRoute({
  path: "/projects",
  getParentRoute: () => redirectRoute,
  component: RedirectProjects,
});

const routeTree = rootRoute.addChildren([
  workspaceGuardPartialLayoutRoute.addChildren([
    getStartedRoute,
    quickstartRoute,
  ]),
  workspaceGuardRoute.addChildren([
    homeRoute,
    workspaceRoute.addChildren([
      projectsRoute.addChildren([
        projectsListRoute,
        projectRoute.addChildren([tracesRoute]),
      ]),
      feedbackDefinitionsRoute.addChildren([feedbackDefinitionsListRoute]),
      experimentsRoute.addChildren([
        experimentsListRoute,
        compareExperimentsRoute,
      ]),
      datasetsRoute.addChildren([
        datasetsListRoute,
        datasetRoute.addChildren([datasetItemsRoute]),
      ]),
      promptsRoute.addChildren([promptsListRoute, promptRoute]),
      redirectRoute.addChildren([redirectProjectRoute]),
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
