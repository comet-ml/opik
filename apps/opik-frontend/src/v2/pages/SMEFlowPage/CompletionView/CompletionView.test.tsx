import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import CompletionView from "./CompletionView";
import { ReactNode } from "react";

// Mock the SMEFlowContext
const mockUseSMEFlow = vi.fn();
const mockHandleReviewAnnotations = vi.fn();

vi.mock("../SMEFlowContext", () => ({
  useSMEFlow: () => mockUseSMEFlow(),
}));

// Mock the child components
vi.mock("../SMEFlowLayout", () => ({
  default: ({
    children,
    footer,
  }: {
    children: ReactNode;
    footer: ReactNode;
  }) => (
    <div>
      <div data-testid="layout-children">{children}</div>
      <div data-testid="layout-footer">{footer}</div>
    </div>
  ),
}));

vi.mock("../ReturnToAnnotationQueueButton", () => ({
  default: () => <div data-testid="return-button" />,
}));

describe("CompletionView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseSMEFlow.mockReturnValue({
      handleReviewAnnotations: mockHandleReviewAnnotations,
    });
  });

  it("should render completion message and UI elements", () => {
    render(<CompletionView header={<div>Header</div>} />);

    expect(screen.getByText("All items completed!")).toBeInTheDocument();
    expect(
      screen.getByText(/All annotations in this queue are complete/),
    ).toBeInTheDocument();

    expect(screen.getByText("🎉")).toBeInTheDocument();

    const reviewButton = screen.getByRole("button", {
      name: /review annotations/i,
    });
    expect(reviewButton).toBeInTheDocument();
    expect(reviewButton.querySelector("svg")).toBeInTheDocument();

    expect(screen.getByTestId("return-button")).toBeInTheDocument();
  });

  it("should call handleReviewAnnotations when review button is clicked", () => {
    render(<CompletionView header={<div>Header</div>} />);

    const reviewButton = screen.getByRole("button", {
      name: /review annotations/i,
    });
    fireEvent.click(reviewButton);

    expect(mockHandleReviewAnnotations).toHaveBeenCalledTimes(1);
  });
});
