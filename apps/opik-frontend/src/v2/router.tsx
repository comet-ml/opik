import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
} from "@tanstack/react-router";

const rootRoute = createRootRoute({
  component: () => <Outlet />,
});

const indexRoute = createRoute({
  path: "/",
  getParentRoute: () => rootRoute,
  component: () => <div>Opik v2</div>,
});

const catchAllRoute = createRoute({
  path: "$",
  getParentRoute: () => rootRoute,
  component: () => <div>Opik v2</div>,
});

const routeTree = rootRoute.addChildren([indexRoute, catchAllRoute]);

export const router = createRouter({
  basepath: import.meta.env.VITE_BASE_URL || "/",
  routeTree,
});

export type AppRouter = typeof router;
