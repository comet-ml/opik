import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { reloadWindowRemainingMs } from "@/lib/chunk-load-recovery";
import RouteErrorFallback from "./RouteErrorFallback";

vi.mock("@/lib/chunk-load-recovery", () => ({
  reloadWindowRemainingMs: vi.fn(),
}));

const renderFallback = () =>
  render(
    <RouteErrorFallback
      error={new Error("Failed to fetch dynamically imported module")}
      reset={vi.fn()}
    />,
  );

describe("RouteErrorFallback", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.mocked(reloadWindowRemainingMs).mockReset();
  });

  it("shows a spinner for the rest of the reload window, then the error if the page was not replaced", () => {
    vi.mocked(reloadWindowRemainingMs).mockReturnValue(4_000);
    renderFallback();

    expect(screen.getByText("Loading")).toBeInTheDocument();
    expect(screen.queryByText("Something went wrong!")).not.toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(3_999);
    });
    expect(screen.getByText("Loading")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(screen.queryByText("Loading")).not.toBeInTheDocument();
    expect(screen.getByText("Something went wrong!")).toBeInTheDocument();
  });

  it("still ends the spinner when the window runs out between render and effect", () => {
    vi.mocked(reloadWindowRemainingMs)
      .mockReturnValueOnce(1)
      .mockReturnValue(0);
    renderFallback();

    expect(screen.getByText("Loading")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(0);
    });
    expect(screen.getByText("Something went wrong!")).toBeInTheDocument();
  });

  it("shows the error right away when no reload is pending", () => {
    vi.mocked(reloadWindowRemainingMs).mockReturnValue(0);
    renderFallback();

    expect(screen.getByText("Something went wrong!")).toBeInTheDocument();
    expect(screen.queryByText("Loading")).not.toBeInTheDocument();
  });
});
