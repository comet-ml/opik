import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { MediaUrlCheckResult } from "@/api/playground/useMediaUrlsCheckMutation";
import MediaUrlCheckDialog from "./MediaUrlCheckDialog";

const failures: MediaUrlCheckResult[] = Array.from(
  { length: 7 },
  (_, index) => ({
    url: `https://cdn.example.com/${index}.png`,
    status: "http_error",
    http_status: 404,
  }),
);

const renderDialog = (items: MediaUrlCheckResult[] | null) => {
  const onRunAnyway = vi.fn();
  const onCancel = vi.fn();
  render(
    <MediaUrlCheckDialog
      failures={items}
      onRunAnyway={onRunAnyway}
      onCancel={onCancel}
    />,
  );
  return { onRunAnyway, onCancel };
};

describe("MediaUrlCheckDialog", () => {
  it("renders nothing without failures", () => {
    renderDialog(null);

    expect(screen.queryByTestId("media-url-check-dialog")).toBeNull();
  });

  it("lists the first five failed URLs with their reason and counts the rest", () => {
    renderDialog(failures);

    expect(screen.getByText("https://cdn.example.com/0.png")).toBeTruthy();
    expect(screen.getByText("https://cdn.example.com/4.png")).toBeTruthy();
    expect(screen.queryByText("https://cdn.example.com/5.png")).toBeNull();
    expect(screen.getAllByText("The host answered with HTTP 404")).toHaveLength(
      5,
    );
    expect(screen.getByText("And 2 more")).toBeTruthy();
  });

  it("calls onRunAnyway from Run anyway and onCancel from Cancel", () => {
    const { onRunAnyway, onCancel } = renderDialog(failures.slice(0, 1));

    fireEvent.click(screen.getByRole("button", { name: "Run anyway" }));
    expect(onRunAnyway).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
