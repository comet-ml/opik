import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  MockInstance,
  vi,
} from "vitest";
import { fireEvent, render, renderHook, screen } from "@testing-library/react";
import { Toast, ToastProvider, ToastViewport } from "@/ui/toast";
import { LogExperiment } from "@/types/playground";
import useRunCompletionToast from "@/v2/pages/PlaygroundPage/useRunCompletionToast";

const toastMock = vi.fn();

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: toastMock }),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({ activeWorkspaceName: "test-workspace" }),
  ),
  useActiveProjectId: () => "project-id",
}));

vi.mock("@/store/PlaygroundStore", () => ({
  default: {
    getState: () => ({
      experimentName: "",
      lastSuggestedExperimentName: null,
    }),
  },
  useSetSuggestedExperimentName: () => vi.fn(),
}));

const createExperiment = (id: string): LogExperiment => ({
  id,
  name: `experiment-${id}`,
  datasetName: "dataset",
});

const announceAndRenderToast = (experiments: LogExperiment[]) => {
  const { result } = renderHook(() => useRunCompletionToast("dataset-id"));
  result.current(experiments);

  const { actions } = toastMock.mock.calls[0][0];
  render(
    <ToastProvider>
      <Toast open>{actions}</Toast>
      <ToastViewport />
    </ToastProvider>,
  );
};

const openedExperimentIds = (openSpy: MockInstance<typeof window.open>) => {
  const url = new URL(String(openSpy.mock.calls[0][0]));
  expect(url.pathname).toBe(
    "/test-workspace/projects/project-id/experiments/dataset-id/compare",
  );
  return JSON.parse(url.searchParams.get("experiments") ?? "[]");
};

describe("useRunCompletionToast", () => {
  let openSpy: MockInstance<typeof window.open>;

  beforeEach(() => {
    toastMock.mockReset();
    openSpy = vi.spyOn(window, "open").mockImplementation(() => null);
  });

  afterEach(() => {
    openSpy.mockRestore();
  });

  it("offers View experiment when a single experiment ran", () => {
    announceAndRenderToast([createExperiment("exp-1")]);

    expect(screen.queryByRole("button", { name: /Compare/ })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "View experiment" }));

    expect(openedExperimentIds(openSpy)).toEqual(["exp-1"]);
  });

  it("offers Compare when several experiments ran", () => {
    announceAndRenderToast([
      createExperiment("exp-1"),
      createExperiment("exp-2"),
    ]);

    expect(
      screen.queryByRole("button", { name: "View experiment" }),
    ).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Compare" }));

    expect(openedExperimentIds(openSpy)).toEqual(["exp-1", "exp-2"]);
  });
});
