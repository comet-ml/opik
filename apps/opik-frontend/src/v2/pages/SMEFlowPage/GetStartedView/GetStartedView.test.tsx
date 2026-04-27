import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { fireEvent } from "@testing-library/react";
import GetStartedView from "./GetStartedView";
import { ReactNode } from "react";

const mockUseSMEFlow = vi.fn();
const mockHandleStartAnnotating = vi.fn();
const mockHandleReviewAnnotations = vi.fn();

vi.mock("../SMEFlowContext", () => ({
  useSMEFlow: () => mockUseSMEFlow(),
}));

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

vi.mock("@/v2/pages-shared/annotation-queues/InstructionsContent", () => ({
  default: () => <div data-testid="instructions-content" />,
}));

vi.mock("@/v2/pages-shared/annotation-queues/ScoresContent", () => ({
  default: () => <div data-testid="scores-content" />,
}));

const baseQueue = {
  id: "queue-1",
  name: "Test Queue",
  scope: "TRACE",
  feedback_definition_names: ["score1"],
};

const baseContext = {
  annotationQueue: baseQueue,
  canStartAnnotation: true,
  handleStartAnnotating: mockHandleStartAnnotating,
  handleReviewAnnotations: mockHandleReviewAnnotations,
  processedCount: 0,
  totalCount: 5,
};

describe("GetStartedView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("alert visibility", () => {
    it("does not show the 'all processed' alert during initial load when totalCount is 0", () => {
      mockUseSMEFlow.mockReturnValue({
        ...baseContext,
        canStartAnnotation: false,
        processedCount: 0,
        totalCount: 0,
      });

      render(<GetStartedView />);

      expect(
        screen.queryByText(
          /All items in this annotation queue have already been processed/,
        ),
      ).not.toBeInTheDocument();
    });

    it("shows the 'all processed' alert when all items are processed and totalCount > 0", () => {
      mockUseSMEFlow.mockReturnValue({
        ...baseContext,
        canStartAnnotation: false,
        processedCount: 5,
        totalCount: 5,
      });

      render(<GetStartedView />);

      expect(
        screen.getByText(
          /All items in this annotation queue have already been processed/,
        ),
      ).toBeInTheDocument();
    });

    it("does not show the alert when there are unprocessed items", () => {
      mockUseSMEFlow.mockReturnValue({
        ...baseContext,
        canStartAnnotation: true,
        processedCount: 2,
        totalCount: 5,
      });

      render(<GetStartedView />);

      expect(
        screen.queryByText(
          /All items in this annotation queue have already been processed/,
        ),
      ).not.toBeInTheDocument();
    });
  });

  describe("footer button states", () => {
    it('shows "Start annotating" when no items have been processed', () => {
      mockUseSMEFlow.mockReturnValue({
        ...baseContext,
        canStartAnnotation: true,
        processedCount: 0,
        totalCount: 5,
      });

      render(<GetStartedView />);

      expect(
        screen.getByRole("button", { name: "Start annotating" }),
      ).toBeInTheDocument();
    });

    it('shows "Resume annotating" when some items have been processed', () => {
      mockUseSMEFlow.mockReturnValue({
        ...baseContext,
        canStartAnnotation: true,
        processedCount: 2,
        totalCount: 5,
      });

      render(<GetStartedView />);

      expect(
        screen.getByRole("button", { name: "Resume annotating" }),
      ).toBeInTheDocument();
    });

    it('shows "Review annotations" when all items are completed', () => {
      mockUseSMEFlow.mockReturnValue({
        ...baseContext,
        canStartAnnotation: false,
        processedCount: 5,
        totalCount: 5,
      });

      render(<GetStartedView />);

      expect(
        screen.getByRole("button", { name: "Review annotations" }),
      ).toBeInTheDocument();
    });

    it('calls handleStartAnnotating when "Start annotating" is clicked', () => {
      mockUseSMEFlow.mockReturnValue({
        ...baseContext,
        canStartAnnotation: true,
        processedCount: 0,
        totalCount: 5,
      });

      render(<GetStartedView />);

      fireEvent.click(screen.getByRole("button", { name: "Start annotating" }));
      expect(mockHandleStartAnnotating).toHaveBeenCalledTimes(1);
    });

    it('calls handleReviewAnnotations when "Review annotations" is clicked', () => {
      mockUseSMEFlow.mockReturnValue({
        ...baseContext,
        canStartAnnotation: false,
        processedCount: 5,
        totalCount: 5,
      });

      render(<GetStartedView />);

      fireEvent.click(
        screen.getByRole("button", { name: "Review annotations" }),
      );
      expect(mockHandleReviewAnnotations).toHaveBeenCalledTimes(1);
    });
  });

  it("returns null when annotationQueue is undefined", () => {
    mockUseSMEFlow.mockReturnValue({
      ...baseContext,
      annotationQueue: undefined,
    });

    const { container } = render(<GetStartedView />);
    expect(container.firstChild).toBeNull();
  });
});
