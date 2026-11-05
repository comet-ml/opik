import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import { DYNAMIC_COLUMN_TYPE } from "@/types/shared";
import { DatasetItemColumn } from "@/types/datasets";
import useTestSuiteDraftStore from "@/store/TestSuiteDraftStore";
import AddDatasetItemPanel from "./AddDatasetItemPanel";

const renderPanel = (columns: DatasetItemColumn[], onClose = vi.fn()) =>
  render(
    <TooltipProvider>
      <AddDatasetItemPanel open onClose={onClose} columns={columns} />
    </TooltipProvider>,
  );

const addedItems = () =>
  Array.from(useTestSuiteDraftStore.getState().addedItems.values());

const addRecordButton = () =>
  screen.getByRole("button", { name: /add record/i });

describe("AddDatasetItemPanel", () => {
  beforeEach(() => {
    useTestSuiteDraftStore.getState().clearDraft();
  });

  it("waits for the dataset columns before showing fields", () => {
    render(
      <TooltipProvider>
        <AddDatasetItemPanel
          open
          onClose={vi.fn()}
          columns={[]}
          isColumnsLoading
        />
      </TooltipProvider>,
    );

    expect(screen.queryByTestId("add-record-starter")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /add record/i }),
    ).not.toBeInTheDocument();
  });

  describe("empty dataset", () => {
    it("offers starter fields and explains why it can't save yet", () => {
      renderPanel([]);

      expect(screen.getByTestId("add-record-starter")).toBeInTheDocument();
      expect(
        screen.getByText(/enter a value in at least one field/i),
      ).toBeInTheDocument();
      expect(addRecordButton()).toBeDisabled();
    });

    it("inserts a starter field and hides the intro card", () => {
      renderPanel([]);

      fireEvent.click(screen.getByText("Expected output"));

      expect(
        screen.queryByTestId("add-record-starter"),
      ).not.toBeInTheDocument();
      expect(screen.getByText("expected_output")).toBeInTheDocument();
      expect(screen.getByLabelText("expected_output value")).toHaveFocus();
    });

    it("saves a record built from a custom field", () => {
      const onClose = vi.fn();
      renderPanel([], onClose);

      fireEvent.click(screen.getByRole("button", { name: /add field/i }));
      fireEvent.change(screen.getByLabelText("Field name"), {
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

    it("prefills schema fields and omits empty ones on save", () => {
      renderPanel(columns);

      expect(
        screen.queryByTestId("add-record-starter"),
      ).not.toBeInTheDocument();
      fireEvent.change(screen.getByLabelText("score value"), {
        target: { value: "7" },
      });
      fireEvent.click(addRecordButton());

      expect(addedItems()[0].data).toEqual({ score: 7 });
    });

    it("blocks saving while a field name is duplicated", () => {
      renderPanel(columns);

      fireEvent.change(screen.getByLabelText("input value"), {
        target: { value: "hi" },
      });
      fireEvent.click(screen.getByRole("button", { name: /add field/i }));
      fireEvent.change(screen.getByLabelText("Field name"), {
        target: { value: "input" },
      });

      expect(
        screen.getByText("Field name is already used"),
      ).toBeInTheDocument();
      expect(addRecordButton()).toBeDisabled();
    });

    it("keeps the field set but clears values after save & add another", () => {
      const onClose = vi.fn();
      renderPanel(columns, onClose);

      fireEvent.change(screen.getByLabelText("input value"), {
        target: { value: "first" },
      });
      fireEvent.click(
        screen.getByRole("button", { name: /save & add another/i }),
      );

      expect(addedItems()).toHaveLength(1);
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByLabelText("input value")).toHaveValue("");
      expect(screen.getByLabelText("score value")).toBeInTheDocument();
    });

    it("submits with the keyboard shortcut", () => {
      renderPanel(columns);

      const input = screen.getByLabelText("input value");
      fireEvent.change(input, { target: { value: "hi" } });
      fireEvent.keyDown(input, { key: "Enter", ctrlKey: true });

      expect(addedItems()).toHaveLength(1);
    });
  });
});
