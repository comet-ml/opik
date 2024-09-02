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
import DatasetExperimentsPage from "@/components/pages/DatasetExperimentsPage/DatasetExperimentsPage";
import DatasetCompareExperimentsPage from "@/components/pages/DatasetCompareExperimentsPage/DatasetCompareExperimentsPage";
import FeedbackDefinitionsPage from "@/components/pages/FeedbackDefinitionsPage/FeedbackDefinitionsPage";
import GetStartedPage from "@/components/pages/GetStartedPage/GetStartedPage";
import HomePage from "@/components/pages/HomePage/HomePage";
import ProjectPage from "@/components/pages/ProjectPage/ProjectPage";
import ProjectsPage from "@/components/pages/ProjectsPage/ProjectsPage";
import TracesPage from "@/components/pages/TracesPage/TracesPage";
import WorkspacePage from "@/components/pages/WorkspacePage/WorkspacePage";

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

// ----------- get started
const getStartedRoute = createRoute({
  path: "/get-started",
  getParentRoute: () => workspaceRoute,
  component: GetStartedPage,
  staticData: {
    title: "Get Started",
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
});

const datasetExperimentsRoute = createRoute({
  path: "/experiments",
  getParentRoute: () => datasetRoute,
  component: DatasetExperimentsPage,
});

const datasetCompareExperimentsRoute = createRoute({
  path: "/compare",
  getParentRoute: () => datasetRoute,
  component: DatasetCompareExperimentsPage,
  staticData: {
    title: "Compare",
  },
});

const routeTree = rootRoute.addChildren([
  workspaceGuardRoute.addChildren([
    homeRoute,
    workspaceRoute.addChildren([
      getStartedRoute,
      projectsRoute.addChildren([
        projectsListRoute,
        projectRoute.addChildren([tracesRoute]),
      ]),
      feedbackDefinitionsRoute.addChildren([feedbackDefinitionsListRoute]),
      datasetsRoute.addChildren([
        datasetsListRoute,
        datasetRoute.addChildren([
          datasetItemsRoute,
          datasetExperimentsRoute,
          datasetCompareExperimentsRoute,
        ]),
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
