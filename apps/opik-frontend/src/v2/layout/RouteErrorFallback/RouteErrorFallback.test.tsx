import { afterEach, describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import useChunkReloadStore from "@/store/ChunkReloadStore";
import RouteErrorFallback from "./RouteErrorFallback";

const renderFallback = () =>
  render(
    <RouteErrorFallback
      error={new Error("Failed to fetch dynamically imported module")}
      reset={vi.fn()}
    />,
  );

describe("RouteErrorFallback", () => {
  afterEach(() => {
    useChunkReloadStore.setState({ reloadPending: false });
  });

  it("shows a spinner while a reload is pending, then the error once it is not", () => {
    useChunkReloadStore.setState({ reloadPending: true });
    renderFallback();

    expect(screen.getByText("Loading")).toBeInTheDocument();
    expect(screen.queryByText("Something went wrong!")).not.toBeInTheDocument();

    act(() => useChunkReloadStore.setState({ reloadPending: false }));

    expect(screen.queryByText("Loading")).not.toBeInTheDocument();
    expect(screen.getByText("Something went wrong!")).toBeInTheDocument();
  });

  it("shows the error right away when no reload is pending", () => {
    renderFallback();

    expect(screen.getByText("Something went wrong!")).toBeInTheDocument();
    expect(screen.queryByText("Loading")).not.toBeInTheDocument();
  });
});
