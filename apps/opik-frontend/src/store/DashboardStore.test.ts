import { describe, expect, it } from "vitest";
import { useDashboardStore } from "./DashboardStore";

describe("DashboardStore", () => {
  it("keeps the saved widget ids when the dashboard is cleared for a reload", () => {
    useDashboardStore.getState().setSavedWidgetIds(["w-1"]);

    useDashboardStore.getState().clearDashboard();

    expect(useDashboardStore.getState().savedWidgetIds).toEqual(["w-1"]);
  });
});
