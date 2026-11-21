import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

let isOllieAvailable = false;

vi.mock("@/shared/Dashboard/useAskOllieForWidget", () => ({
  default: () => ({ isOllieAvailable, askOllie: vi.fn() }),
}));
vi.mock("@/shared/TooltipWrapper/TooltipWrapper", () => ({
  default: ({
    children,
    content,
  }: {
    children: React.ReactNode;
    content: React.ReactNode;
  }) => (
    <div>
      {children}
      <div data-testid="tooltip">{content}</div>
    </div>
  ),
}));

import DashboardsNavBadge from "./DashboardsNavBadge";

describe("DashboardsNavBadge", () => {
  it("renders nothing where Ollie cannot build widgets", () => {
    isOllieAvailable = false;
    const { container } = render(<DashboardsNavBadge collapsed={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing on the collapsed rail", () => {
    isOllieAvailable = true;
    const { container } = render(<DashboardsNavBadge collapsed />);
    expect(container).toBeEmptyDOMElement();
  });

  it("labels the item New and explains Ollie widgets on hover", () => {
    isOllieAvailable = true;
    render(<DashboardsNavBadge collapsed={false} />);
    expect(screen.getByText("New")).toBeInTheDocument();
    expect(screen.getByTestId("tooltip")).toHaveTextContent(
      "create custom widgets with Ollie",
    );
  });
});
