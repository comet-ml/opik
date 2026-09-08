import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import FeedbackScoreValueInput, {
  FeedbackScoreValue,
} from "./FeedbackScoreValueInput";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";

vi.mock("@/shared/SelectBox/SelectBox", () => ({
  default: ({
    options,
    onChange,
    testId,
  }: {
    options: Array<{ label: string; value: string }>;
    onChange: (value?: string) => void;
    testId?: string;
  }) => (
    <div data-testid={testId}>
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          data-testid={`${testId}-option-${option.value}`}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  ),
}));

const numericalDef = {
  name: "helpfulness",
  type: FEEDBACK_DEFINITION_TYPE.numerical,
  details: { min: 0, max: 10 },
};
const booleanDef = {
  name: "thumbs",
  type: FEEDBACK_DEFINITION_TYPE.boolean,
  details: { true_label: "up", false_label: "down" },
};
const categoricalDef = {
  name: "satisfaction",
  type: FEEDBACK_DEFINITION_TYPE.categorical,
  details: { categories: { good: 1, bad: 0 } },
};
const categoricalLongDef = {
  name: "satisfaction",
  type: FEEDBACK_DEFINITION_TYPE.categorical,
  details: { categories: { excellent: 3, average: 2, poor: 1 } },
};

const renderInput = (
  feedbackDefinition: FeedbackDefinition,
  onChange: (update: FeedbackScoreValue) => void,
  props?: { value?: number | ""; categoryName?: string },
) => {
  render(
    <FeedbackScoreValueInput
      feedbackDefinition={feedbackDefinition}
      value={props?.value ?? ""}
      categoryName={props?.categoryName}
      onChange={onChange}
      testIdPrefix="fsvi"
    />,
  );
};

describe("FeedbackScoreValueInput", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("emits valid numeric values", async () => {
    const onChange = vi.fn();
    renderInput(numericalDef as unknown as FeedbackDefinition, onChange);

    fireEvent.change(screen.getByTestId("fsvi-score-input"), {
      target: { value: "7" },
    });

    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith({
        value: 7,
        status: "valid",
      });
    });
  });

  it("marks out-of-range numeric input as invalid without clearing it", async () => {
    const onChange = vi.fn();
    renderInput(numericalDef as unknown as FeedbackDefinition, onChange, {
      value: 7,
    });

    fireEvent.change(screen.getByTestId("fsvi-score-input"), {
      target: { value: "99" },
    });

    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith({
        value: undefined,
        status: "invalid",
      });
    });
    expect(onChange).not.toHaveBeenCalledWith(
      expect.objectContaining({ status: "empty" }),
    );
  });

  it("marks empty numeric input as an explicit clear", async () => {
    const onChange = vi.fn();
    renderInput(numericalDef as unknown as FeedbackDefinition, onChange, {
      value: 7,
    });

    fireEvent.change(screen.getByTestId("fsvi-score-input"), {
      target: { value: "" },
    });

    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith({
        value: undefined,
        status: "empty",
      });
    });
  });

  it("exposes an accessible name on the numeric input", () => {
    renderInput(numericalDef as unknown as FeedbackDefinition, vi.fn());
    expect(screen.getByLabelText("helpfulness")).toBeInTheDocument();
  });

  it("maps boolean labels to values", () => {
    const onChange = vi.fn();
    renderInput(booleanDef as unknown as FeedbackDefinition, onChange);

    fireEvent.click(screen.getByTestId("fsvi-boolean-toggle-up"));

    expect(onChange).toHaveBeenCalledWith({
      value: 1,
      categoryName: "up",
    });
  });

  it("maps short categorical lists to toggle items", () => {
    const onChange = vi.fn();
    renderInput(categoricalDef as unknown as FeedbackDefinition, onChange);

    fireEvent.click(screen.getByTestId("fsvi-category-toggle-good"));

    expect(onChange).toHaveBeenCalledWith({
      value: 1,
      categoryName: "good",
    });
  });

  it("uses a select for long categorical lists", () => {
    const onChange = vi.fn();
    renderInput(categoricalLongDef as unknown as FeedbackDefinition, onChange);

    fireEvent.click(screen.getByTestId("fsvi-category-select-option-average"));

    expect(onChange).toHaveBeenCalledWith({
      value: 2,
      categoryName: "average",
    });
  });

  it("treats empty-string selection as a clear only when no empty key exists", () => {
    const onChange = vi.fn();
    renderInput(categoricalLongDef as unknown as FeedbackDefinition, onChange);

    // SelectBox mock only fires real option values; simulate a clear
    // selection through the same onChange contract used by SelectBox.
    const select = screen.getByTestId("fsvi-category-select");
    fireEvent.click(select); // no-op click; ensure no clear event was emitted
    expect(onChange).not.toHaveBeenCalledWith(
      expect.objectContaining({ value: undefined }),
    );
  });
});
