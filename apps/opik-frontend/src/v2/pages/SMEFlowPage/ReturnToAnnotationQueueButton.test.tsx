import { describe, it, expect, vi, beforeEach } from "vitest";
import { render } from "@testing-library/react";
import ReturnToAnnotationQueueButton from "./ReturnToAnnotationQueueButton";
import { ReactNode } from "react";

const mockFlushPendingChanges = vi.fn();
const mockUseSMEFlow = vi.fn();

vi.mock("./SMEFlowContext", () => ({
  useSMEFlow: () => mockUseSMEFlow(),
}));

interface AppStoreState {
  activeWorkspaceName: string;
  activeProjectId: string;
}

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: AppStoreState) => string) =>
    selector({
      activeWorkspaceName: "test-workspace",
      activeProjectId: "test-project-id",
    }),
}));

interface MockLinkProps {
  children: ReactNode;
  to: string;
  onClick?: () => void;
  params: {
    workspaceName: string;
    annotationQueueId: string;
  };
}

const mockLink = vi.fn(({ children, to, params, onClick }: MockLinkProps) => (
  <div
    data-testid="link"
    data-to={to}
    data-params={JSON.stringify(params)}
    onClick={onClick}
  >
    {children}
  </div>
));

vi.mock("@tanstack/react-router", () => ({
  Link: (props: MockLinkProps) => mockLink(props),
}));

describe("ReturnToAnnotationQueueButton", () => {
  const defaultContextValue = {
    annotationQueue: { id: "queue-123", project_id: "test-project-id" },
    flushPendingChanges: mockFlushPendingChanges,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    mockUseSMEFlow.mockReturnValue(defaultContextValue);
  });

  it("should navigate to the specific annotation queue page", () => {
    render(<ReturnToAnnotationQueueButton />);

    expect(mockLink).toHaveBeenCalledWith(
      expect.objectContaining({
        to: "/$workspaceName/projects/$projectId/annotation-queues/$annotationQueueId",
        params: {
          workspaceName: "test-workspace",
          projectId: "test-project-id",
          annotationQueueId: "queue-123",
        },
      }),
    );
  });

  it("should flush pending changes on click", () => {
    render(<ReturnToAnnotationQueueButton />);

    expect(mockLink).toHaveBeenCalledWith(
      expect.objectContaining({
        onClick: mockFlushPendingChanges,
      }),
    );
  });
});
