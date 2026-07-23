import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { ActivityType, RecentActivityItem } from "@/types/recent-activity";
import RecentActivitySection from "./RecentActivitySection";

let mockItems: RecentActivityItem[] = [];

vi.mock("@/api/projects/useRecentActivity", () => ({
  default: () => ({ data: { content: mockItems }, isPending: false }),
}));

const PROJECT_ID = "019e3a68-9e79-70a8-adb8-0e04294f9d73";

// Minimal route tree mirroring the real project-scoped leaves the activity
// links target. `basepath` is "/opik" on cloud and "/" on OSS/self-hosted.
async function renderAt(workspaceName: string, basepath = "/opik") {
  const rootRoute = createRootRoute({ component: Outlet });
  const wsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/$workspaceName",
    component: Outlet,
  });
  const projectsRoute = createRoute({
    getParentRoute: () => wsRoute,
    path: "/projects",
    component: Outlet,
  });
  const projectRoute = createRoute({
    getParentRoute: () => projectsRoute,
    path: "/$projectId",
    component: Outlet,
  });
  const homeRoute = createRoute({
    getParentRoute: () => projectRoute,
    path: "/home",
    component: RecentActivitySection,
  });
  const leaf = (path: string) =>
    createRoute({
      getParentRoute: () => projectRoute,
      path,
      component: () => null,
    });

  const routeTree = rootRoute.addChildren([
    wsRoute.addChildren([
      projectsRoute.addChildren([
        projectRoute.addChildren([
          homeRoute,
          leaf("/logs"),
          leaf("/optimizations/$optimizationId"),
          leaf("/experiments/$datasetId/compare"),
          leaf("/datasets/$datasetId/items"),
          leaf("/test-suites/$suiteId/items"),
          leaf("/prompts/$promptId"),
          leaf("/alerts/$alertId"),
        ]),
      ]),
    ]),
  ]);

  const prefix = basepath === "/" ? "" : basepath;
  const router = createRouter({
    routeTree,
    basepath,
    history: createMemoryHistory({
      initialEntries: [
        `${prefix}/${workspaceName}/projects/${PROJECT_ID}/home`,
      ],
    }),
  });

  await router.load();
  render(<RouterProvider router={router} />);
}

const hrefByText = (text: string) =>
  screen.getByText(text).closest("a")!.getAttribute("href");

const ALL_ACTIVITY_ITEMS: RecentActivityItem[] = [
  {
    type: ActivityType.TRACE_DAILY,
    id: "t-1",
    name: "traces",
    created_at: "2026-07-23T10:00:00Z",
  },
  {
    type: ActivityType.OPTIMIZATION,
    id: "opt-1",
    name: "My optimization",
    created_at: "2026-07-23T10:00:00Z",
  },
  {
    type: ActivityType.EXPERIMENT,
    id: "exp-1",
    name: "My experiment",
    created_at: "2026-07-23T10:00:00Z",
    resource_id: "dataset-1",
  },
  {
    type: ActivityType.DATASET_VERSION,
    id: "ds-1",
    name: "My dataset",
    created_at: "2026-07-23T10:00:00Z",
  },
  {
    type: ActivityType.TEST_SUITE_VERSION,
    id: "ts-1",
    name: "My suite",
    created_at: "2026-07-23T10:00:00Z",
  },
  {
    type: ActivityType.PROMPT_VERSION,
    id: "pr-1",
    name: "My prompt",
    created_at: "2026-07-23T10:00:00Z",
  },
  {
    type: ActivityType.ALERT_EVENT,
    id: "al-1",
    name: "My alert",
    created_at: "2026-07-23T10:00:00Z",
  },
];

function expectAllHrefs(workspaceName: string, prefix = "/opik") {
  const base = `${prefix}/${workspaceName}/projects/${PROJECT_ID}`;

  expect(hrefByText("traces")).toBe(`${base}/logs?logsType=traces`);
  expect(hrefByText("My optimization")).toBe(`${base}/optimizations/opt-1`);
  expect(hrefByText("My experiment")).toBe(
    `${base}/experiments/dataset-1/compare?experiments=%5B%22exp-1%22%5D`,
  );
  expect(hrefByText("My dataset")).toBe(`${base}/datasets/ds-1/items`);
  expect(hrefByText("My suite")).toBe(`${base}/test-suites/ts-1/items`);
  expect(hrefByText("My prompt")).toBe(`${base}/prompts/pr-1`);
  expect(hrefByText("My alert")).toBe(`${base}/alerts/al-1`);
}

// Cloud deployments (.env.comet) serve the app under basepath "/opik".
describe("RecentActivitySection link construction — cloud (basepath /opik)", () => {
  beforeEach(() => {
    mockItems = [];
  });

  // Regression: a workspace whose name starts with "opik" collided with the
  // "/opik" basepath. TanStack's removeBasepath does an unbounded
  // pathname.replace(basepath, ""), so a string-concatenated
  // to="/opik-testing/..." was mangled to "/opik/-testing/...".
  it("does not mangle a workspace name that starts with the basepath", async () => {
    mockItems = ALL_ACTIVITY_ITEMS;
    await renderAt("opik-testing", "/opik");

    expect(hrefByText("My experiment")).not.toContain("/opik/-testing");
    expectAllHrefs("opik-testing", "/opik");
  });

  // A workspace name that does not collide with the basepath must be unaffected
  // by the fix.
  it("builds correct hrefs for a normal workspace name", async () => {
    mockItems = ALL_ACTIVITY_ITEMS;
    await renderAt("andreicc", "/opik");

    expectAllHrefs("andreicc", "/opik");
  });
});

// OSS / self-hosted / local deployments (.env.production, .env.development)
// serve the app under basepath "/". There is no "/opik" prefix, so the links
// must resolve to "/{workspace}/projects/...".
describe("RecentActivitySection link construction — OSS/self-hosted (basepath /)", () => {
  beforeEach(() => {
    mockItems = [];
  });

  it("builds correct hrefs for a normal workspace name", async () => {
    mockItems = ALL_ACTIVITY_ITEMS;
    await renderAt("default", "/");

    expectAllHrefs("default", "");
  });

  // A workspace literally named "opik" is only a hazard when a "/opik" basepath
  // is stripped; on OSS (basepath "/") nothing is stripped, so it must resolve
  // cleanly to "/opik/projects/...".
  it("builds correct hrefs for a workspace named 'opik'", async () => {
    mockItems = ALL_ACTIVITY_ITEMS;
    await renderAt("opik", "/");

    expectAllHrefs("opik", "");
  });
});
