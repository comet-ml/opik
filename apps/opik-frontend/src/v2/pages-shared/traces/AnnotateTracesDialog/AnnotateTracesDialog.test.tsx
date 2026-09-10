import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import AnnotateTracesDialog from "./AnnotateTracesDialog";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { Span, Trace } from "@/types/traces";

const { mutateAsync, toast, setOpen, definitions } = vi.hoisted(() => ({
  mutateAsync: vi.fn(),
  toast: vi.fn(),
  setOpen: vi.fn(),
  definitions: [
    {
      id: "quality",
      name: "Quality",
      type: "numerical",
      details: { min: 0, max: 1 },
    },
    {
      id: "category",
      name: "Category",
      type: "categorical",
      details: { categories: { Bad: 0, Good: 2 } },
    },
    {
      id: "boolean",
      name: "Correct",
      type: "boolean",
      details: { true_label: "Yes", false_label: "No" },
    },
  ],
}));

vi.mock("@/api/feedback-definitions/useFeedbackDefinitionsList", () => ({
  default: () => ({ data: { content: definitions }, isPending: false }),
}));
vi.mock("@/api/traces/useTraceFeedbackScoreSetMutation", () => ({
  default: () => ({ mutateAsync }),
}));
vi.mock("@/ui/use-toast", () => ({ useToast: () => ({ toast }) }));
vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "workspace" }),
}));

const rows = [{ id: "trace-1" }, { id: "trace-2" }] as Trace[];
const renderDialog = (
  type = TRACE_DATA_TYPE.traces,
  selectedRows: Array<Trace | Span> = rows,
) =>
  render(
    <AnnotateTracesDialog
      rows={selectedRows}
      type={type}
      open
      setOpen={setOpen}
    />,
  );
const apply = () => screen.getByTestId("annotate-bulk-apply-button");
const selectDefinition = async (name: string) => {
  fireEvent.keyDown(screen.getByTestId("annotate-bulk-score-select"), {
    key: "Enter",
  });
  fireEvent.keyDown(
    await screen.findByTestId(`annotate-bulk-score-select-option-${name}`),
    { key: "Enter" },
  );
};
const enterScore = (value: string) =>
  fireEvent.change(screen.getByTestId("annotate-bulk-score-input"), {
    target: { value },
  });

describe("AnnotateTracesDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mutateAsync.mockResolvedValue({});
  });

  it("requires a definition and an in-range numerical value, including zero", async () => {
    renderDialog();
    expect(screen.getByText("Annotate traces")).toBeInTheDocument();
    expect(screen.getByText("2 selected")).toBeInTheDocument();
    expect(apply()).toBeDisabled();
    await selectDefinition("Quality");
    expect(apply()).toBeDisabled();
    for (const value of ["-0.1", "1.1", ""]) {
      enterScore(value);
      expect(apply()).toBeDisabled();
    }
    for (const value of ["0", "0.5", "1"]) {
      enterScore(value);
      expect(apply()).toBeEnabled();
    }
  });

  it("applies a numerical score and reason to every trace and closes on success", async () => {
    renderDialog();
    await selectDefinition("Quality");
    enterScore("0");
    fireEvent.change(screen.getByTestId("annotate-bulk-reason-input"), {
      target: { value: "Reviewed" },
    });
    fireEvent.click(apply());
    await waitFor(() => expect(setOpen).toHaveBeenCalledWith(false));
    for (const row of rows) {
      expect(mutateAsync).toHaveBeenCalledWith({
        traceId: row.id,
        name: "Quality",
        value: 0,
        reason: "Reviewed",
        categoryName: undefined,
      });
    }
    expect(mutateAsync).toHaveBeenCalledTimes(2);
    expect(toast).toHaveBeenCalledWith({ title: "Annotations applied" });
  });

  it("applies categorical values to spans with their parent trace IDs", async () => {
    const spans = [
      { id: "span-1", trace_id: "trace-1" },
      { id: "span-2", trace_id: "trace-2" },
    ] as Span[];
    renderDialog(TRACE_DATA_TYPE.spans, spans);
    expect(screen.getByText("Annotate spans")).toBeInTheDocument();
    await selectDefinition("Category");
    fireEvent.click(screen.getByTestId("annotate-bulk-category-toggle-Good"));
    fireEvent.click(apply());
    await waitFor(() => expect(setOpen).toHaveBeenCalledWith(false));
    for (const row of spans) {
      expect(mutateAsync).toHaveBeenCalledWith({
        traceId: row.trace_id,
        spanId: row.id,
        name: "Category",
        value: 2,
        categoryName: "Good",
        reason: undefined,
      });
    }
  });

  it.each([
    ["Yes", 1],
    ["No", 0],
  ])("uses boolean label %s and value %s", async (label, value) => {
    renderDialog();
    await selectDefinition("Correct");
    fireEvent.click(
      screen.getByTestId(`annotate-bulk-category-toggle-${label}`),
    );
    fireEvent.click(apply());
    await waitFor(() => expect(setOpen).toHaveBeenCalledWith(false));
    expect(mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ name: "Correct", value, categoryName: label }),
    );
  });

  it("clears the score when switching definitions and when deselecting a category", async () => {
    renderDialog();
    await selectDefinition("Quality");
    enterScore("0.5");
    await selectDefinition("Category");
    expect(apply()).toBeDisabled();
    const toggle = screen.getByTestId("annotate-bulk-category-toggle-Bad");
    fireEvent.click(toggle);
    expect(apply()).toBeEnabled();
    fireEvent.click(toggle);
    expect(apply()).toBeDisabled();
    await selectDefinition("Quality");
    expect(screen.getByTestId("annotate-bulk-score-input")).toHaveValue(null);
    expect(apply()).toBeDisabled();
  });

  it("waits for all requests and stays open after a partial failure", async () => {
    let resolve!: (value: unknown) => void;
    mutateAsync
      .mockRejectedValueOnce(new Error("Failed"))
      .mockImplementationOnce(
        () =>
          new Promise((done) => {
            resolve = done;
          }),
      );
    renderDialog();
    await selectDefinition("Quality");
    enterScore("1");
    fireEvent.click(apply());
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
    expect(apply()).toBeDisabled();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(setOpen).not.toHaveBeenCalled();
    await act(async () => resolve({}));
    expect(apply()).toBeEnabled();
    expect(setOpen).not.toHaveBeenCalled();
    expect(toast).not.toHaveBeenCalled();
  });

  it("disables apply without selected rows", async () => {
    renderDialog(TRACE_DATA_TYPE.traces, []);
    await selectDefinition("Quality");
    enterScore("1");
    expect(apply()).toBeDisabled();
  });
});
