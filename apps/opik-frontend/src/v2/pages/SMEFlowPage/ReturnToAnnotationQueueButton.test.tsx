import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import ReturnToAnnotationQueueButton from "./ReturnToAnnotationQueueButton";
import { ReactNode } from "react";

const mockUseSMEFlow = vi.fn();

vi.mock("./SMEFlowContext", () => ({
  useSMEFlow: () => mockUseSMEFlow(),
}));

interface NavigationBlockerOptions {
  condition: boolean;
  title: string;
  description: string;
  confirmText: string;
  cancelText: string;
}

const mockUseNavigationBlocker = vi.fn();

vi.mock("@/hooks/useNavigationBlocker", () => ({
  default: (options: NavigationBlockerOptions) =>
    mockUseNavigationBlocker(options),
}));

interface AppStoreState {
  activeWorkspaceName: string;
}

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: AppStoreState) => string) =>
    selector({ activeWorkspaceName: "test-workspace" }),
}));

interface MockLinkProps {
  children: ReactNode;
  to: string;
  params: {
    workspaceName: string;
    annotationQueueId: string;
  };
}

const mockLink = vi.fn(({ children, to, params }: MockLinkProps) => (
  <div data-testid="link" data-to={to} data-params={JSON.stringify(params)}>
    {children}
  </div>
));

vi.mock("@tanstack/react-router", () => ({
  Link: (props: MockLinkProps) => mockLink(props),
}));

describe("ReturnToAnnotationQueueButton", () => {
  const defaultContextValue = {
    annotationQueue: { id: "queue-123" },
    hasAnyUnsavedChanges: false,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    mockUseSMEFlow.mockReturnValue(defaultContextValue);
    mockUseNavigationBlocker.mockReturnValue({
      DialogComponent: <div data-testid="navigation-blocker-dialog" />,
    });
    mockLink.mockClear();
  });

  it("should navigate to the specific annotation queue page", () => {
    render(<ReturnToAnnotationQueueButton />);

    expect(mockLink).toHaveBeenCalledWith(
      expect.objectContaining({
        to: "/$workspaceName/annotation-queues/$annotationQueueId",
        params: {
          workspaceName: "test-workspace",
          annotationQueueId: "queue-123",
        },
      }),
    );
  });

  it("should render the navigation blocker dialog", () => {
    render(<ReturnToAnnotationQueueButton />);

    expect(screen.getByTestId("navigation-blocker-dialog")).toBeInTheDocument();
  });

  it("should call useNavigationBlocker with hasAnyUnsavedChanges condition", () => {
    // Test without unsaved changes
    mockUseSMEFlow.mockReturnValue({
      ...defaultContextValue,
      hasAnyUnsavedChanges: false,
    });

    const { rerender } = render(<ReturnToAnnotationQueueButton />);

    expect(mockUseNavigationBlocker).toHaveBeenCalledWith({
      condition: false,
      title: "Unsaved changes",
      description:
        "You have unsaved changes to your annotations. If you leave now, your changes will be lost.",
      confirmText: "Leave without saving",
      cancelText: "Stay on page",
    });

    // Test with unsaved changes
    mockUseSMEFlow.mockReturnValue({
      ...defaultContextValue,
      hasAnyUnsavedChanges: true,
    });

    rerender(<ReturnToAnnotationQueueButton />);

    expect(mockUseNavigationBlocker).toHaveBeenCalledWith({
      condition: true,
      title: "Unsaved changes",
      description:
        "You have unsaved changes to your annotations. If you leave now, your changes will be lost.",
      confirmText: "Leave without saving",
      cancelText: "Stay on page",
    });
  });
});
