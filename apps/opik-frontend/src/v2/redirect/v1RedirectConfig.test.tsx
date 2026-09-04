import { describe, it, expect } from "vitest";
import {
  createRootRoute,
  createRoute,
  type AnyRoute,
} from "@tanstack/react-router";
import { createV1RedirectRoutes } from "./v1RedirectConfig";

/**
 * These assert that a compat ROUTE is registered, which is the level the
 * OPIK-7105 defect lived at. V1CompatRedirect.test.tsx exercises the component
 * with an arbitrary `toPath` and so passes for paths that have no route at all —
 * it could not have caught the missing /prompts entry.
 */

const buildRoutes = () => {
  const rootRoute = createRootRoute();
  const workspaceRoute = createRoute({
    path: "/$workspaceName",
    getParentRoute: () => rootRoute,
  });

  return createV1RedirectRoutes(workspaceRoute);
};

// TanStack's RouteOptions union does not surface `path` statically, so read it
// through a narrow shape rather than widening the whole route type.
const pathOf = (route: AnyRoute) => (route.options as { path?: string }).path;

const pathsOf = (routes: AnyRoute[]) => routes.map(pathOf);

describe("createV1RedirectRoutes", () => {
  it("registers a compat route for every workspace-level V1 resource", () => {
    expect(pathsOf(buildRoutes())).toEqual(
      expect.arrayContaining([
        "/experiments",
        "/test-suites",
        "/datasets",
        "/prompts",
        "/playground",
        "/optimizations",
        "/online-evaluation",
        "/annotation-queues",
        "/alerts",
      ]),
    );
  });

  it("registers /prompts, so workspace-level prompt links resolve", () => {
    // The backend mints /$workspaceName/prompts/$promptId in Slack alert
    // payloads for prompt_created and prompt_committed. Without this route the
    // link dead-ends: V2 serves prompts only under project scope and neither
    // router declares a notFoundComponent.
    expect(pathsOf(buildRoutes())).toContain("/prompts");
  });

  it("gives /prompts index and splat children, so /prompts/$promptId resolves", () => {
    const promptsRoute = buildRoutes().find(
      (route) => pathOf(route) === "/prompts",
    );

    expect(promptsRoute).toBeDefined();

    const children = (promptsRoute?.children ?? []) as AnyRoute[];
    expect(children.map(pathOf)).toEqual(expect.arrayContaining(["/", "$"]));
  });
});
