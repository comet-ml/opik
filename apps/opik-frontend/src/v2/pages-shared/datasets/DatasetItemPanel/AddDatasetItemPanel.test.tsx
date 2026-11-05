import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { DatasetItemColumn } from "@/types/datasets";
import useTestSuiteDraftStore from "@/store/TestSuiteDraftStore";
import AddDatasetItemPanel from "./AddDatasetItemPanel";

const renderPanel = (
  columns: DatasetItemColumn[],
  { onClose = vi.fn(), isColumnsLoading = false } = {},
) =>
  render(
    <TooltipProvider>
      <AddDatasetItemPanel
        open
        onClose={onClose}
        columns={columns}
        isColumnsLoading={isColumnsLoading}
      />
    </TooltipProvider>,
  );

const addedItems = () =>
  Array.from(useTestSuiteDraftStore.getState().addedItems.values());

const addRecordButton = () =>
  screen.getByRole("button", { name: /^add record$/i });

const lastFieldNameInput = () => screen.getAllByLabelText("Field name").at(-1)!;

describe("AddDatasetItemPanel", () => {
  beforeEach(() => {
    useTestSuiteDraftStore.getState().clearDraft();
  });

  it("waits for the dataset columns before showing fields", () => {
    renderPanel([], { isColumnsLoading: true });

    expect(screen.queryByTestId("add-record-starter")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /^add record$/i }),
    ).not.toBeInTheDocument();
  });

  it("only offers Cancel and Add record in the footer", () => {
    renderPanel([]);

    expect(
      screen.queryByRole("button", { name: /add another/i }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeInTheDocument();
  });

  describe("empty dataset", () => {
    it("shows the empty state and asks for at least one field", () => {
      renderPanel([]);

      expect(screen.getByTestId("add-record-starter")).toBeInTheDocument();
      expect(screen.getByText("No fields defined")).toBeInTheDocument();
      expect(screen.getByText("Add at least one field")).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: "Add tag" }),
      ).toBeInTheDocument();
      expect(addRecordButton()).toBeDisabled();
    });

    it("inserts a suggested field and focuses its value", () => {
      renderPanel([]);

      fireEvent.click(screen.getByText("Expected output"));

      expect(
        screen.queryByTestId("add-record-starter"),
      ).not.toBeInTheDocument();
      expect(screen.getByText("Item fields")).toBeInTheDocument();
      expect(lastFieldNameInput()).toHaveValue("expected_output");
      expect(screen.getByLabelText("expected_output value")).toHaveFocus();
      expect(screen.getByText("Adding 1 field")).toBeInTheDocument();
      expect(addRecordButton()).toBeEnabled();
    });

    it("saves a record built from a custom field", () => {
      const onClose = vi.fn();
      renderPanel([], { onClose });

      fireEvent.click(screen.getByRole("button", { name: /add field/i }));
      fireEvent.change(lastFieldNameInput(), {
        target: { value: "question" },
      });
      fireEvent.change(screen.getByLabelText("question value"), {
        target: { value: "What is Opik?" },
      });
      fireEvent.click(addRecordButton());

      expect(addedItems()).toHaveLength(1);
      expect(addedItems()[0].data).toEqual({ question: "What is Opik?" });
      expect(onClose).toHaveBeenCalled();
    });
  });

  describe("dataset with columns", () => {
    const columns = [
      { name: "input", types: [DYNAMIC_COLUMN_TYPE.string] },
      { name: "score", types: [DYNAMIC_COLUMN_TYPE.number] },
    ];

    it("prefills schema fields and saves empty ones as empty", () => {
      renderPanel(columns);

      expect(screen.getByText("Adding 2 fields")).toBeInTheDocument();
      fireEvent.change(screen.getByLabelText("score value"), {
        target: { value: "7" },
      });
      fireEvent.click(addRecordButton());

      expect(addedItems()[0].data).toEqual({ input: "", score: 7 });
    });

    it("renames and removes fields before saving", () => {
      renderPanel(columns);

      fireEvent.change(screen.getAllByLabelText("Field name")[0], {
        target: { value: "question" },
      });
      fireEvent.click(screen.getByRole("button", { name: "Remove score" }));
      fireEvent.click(addRecordButton());

      expect(addedItems()[0].data).toEqual({ question: "" });
    });

    it("blocks saving while a field name is duplicated", () => {
      renderPanel(columns);

      fireEvent.click(screen.getByRole("button", { name: /add field/i }));
      fireEvent.change(lastFieldNameInput(), { target: { value: "input" } });

      expect(
        screen.getByText("Field name is already used"),
      ).toBeInTheDocument();
      expect(
        screen.getByText("Fix the field names to add this record"),
      ).toBeInTheDocument();
      expect(addRecordButton()).toBeDisabled();
    });
  });
});
