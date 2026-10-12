import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { FeatureTogglesProvider } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys, FeatureToggles } from "@/types/feature-toggles";
import OlliePage from "./OlliePage";

let mockToggles: { data?: Partial<FeatureToggles>; isError: boolean };

vi.mock("@/api/feature-toggle/useFeatureToggle", () => ({
  default: () => mockToggles,
}));

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "ws" }),
}));

vi.mock("@/store/PluginsStore", () => ({
  default: (
    selector: (state: { AssistantSidebar: React.ComponentType }) => unknown,
  ) => selector({ AssistantSidebar: () => <div>assistant sidebar</div> }),
}));

const OLLIE_PATH = "/ws/projects/p1/ollie";

const renderAtOllie = async () => {
  const rootRoute = createRootRoute({ component: Outlet });
  const projectRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: "/$workspaceName/projects/$projectId",
    component: Outlet,
  });
  const ollieRoute = createRoute({
    getParentRoute: () => projectRoute,
    path: "/ollie",
    component: OlliePage,
  });
  const homeRoute = createRoute({
    getParentRoute: () => projectRoute,
    path: "/home",
    component: () => <div>project home</div>,
  });

  const router = createRouter({
    routeTree: rootRoute.addChildren([
      projectRoute.addChildren([ollieRoute, homeRoute]),
    ]),
    history: createMemoryHistory({ initialEntries: [OLLIE_PATH] }),
  });
  await router.load();

  const tree = () => (
    <FeatureTogglesProvider>
      <RouterProvider router={router} />
    </FeatureTogglesProvider>
  );
  const { rerender } = render(tree());
  return { router, rerender: () => rerender(tree()) };
};

describe("OlliePage", () => {
  beforeEach(() => {
    mockToggles = { data: undefined, isError: false };
  });

  it("stays on the Ollie page while toggles load, then mounts the assistant", async () => {
    const { router, rerender } = await renderAtOllie();

    expect(await screen.findByText("Loading")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(OLLIE_PATH);

    mockToggles = {
      data: { [FeatureToggleKeys.OLLIE_ENABLED]: true },
      isError: false,
    };
    rerender();

    expect(await screen.findByText("assistant sidebar")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(OLLIE_PATH);
  });

  it("redirects to the project home once toggles say Ollie is off", async () => {
    const { router, rerender } = await renderAtOllie();

    mockToggles = {
      data: { [FeatureToggleKeys.OLLIE_ENABLED]: false },
      isError: false,
    };
    rerender();

    await waitFor(() =>
      expect(router.state.location.pathname).toBe("/ws/projects/p1/home"),
    );
  });
});
