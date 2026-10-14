import React from "react";
import { act, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderVegaChart } from "@/lib/charts/vega";
import VegaChart from "./VegaChart";

vi.mock("@/lib/charts/vega", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/charts/vega")>()),
  renderVegaChart: vi.fn(),
}));
vi.mock("@/contexts/theme-provider", () => ({
  useTheme: () => ({ themeMode: "light" }),
}));
vi.mock("@/hooks/useWorkspaceColorMap", () => ({
  default: () => ({ colorMap: null }),
}));
vi.mock("@/store/AppStore", () => ({
  useActiveWorkspaceName: () => "ws",
}));

const renderMock = vi.mocked(renderVegaChart);

describe("VegaChart", () => {
  beforeEach(() => {
    renderMock.mockReset();
  });

  it("draws again after a render error once the spec changes", async () => {
    renderMock.mockRejectedValueOnce(new Error("bad spec"));
    const { rerender } = render(<VegaChart spec={{ mark: "nope" }} />);
    await screen.findByText("bad spec");

    renderMock.mockResolvedValueOnce(() => {});
    await act(async () => {
      rerender(<VegaChart spec={{ mark: "bar" }} />);
    });

    await waitFor(() => expect(screen.queryByText("bad spec")).toBeNull());
    expect(renderMock).toHaveBeenCalledTimes(2);
    expect(renderMock.mock.calls[1][0]).toBeInstanceOf(HTMLElement);
  });
});
