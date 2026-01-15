import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import AddEditFeedbackDefinitionDialog from "./AddEditFeedbackDefinitionDialog";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";

vi.mock(
  "@/api/feedback-definitions/useFeedbackDefinitionCreateMutation",
  () => ({
    default: () => ({
      mutate: vi.fn(),
    }),
  }),
);

vi.mock(
  "@/api/feedback-definitions/useFeedbackDefinitionUpdateMutation",
  () => ({
    default: () => ({
      mutate: vi.fn(),
    }),
  }),
);

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
    }),
  ),
}));

const mockBooleanFeedbackDefinition: FeedbackDefinition = {
  id: "test-id",
  name: "Helpfulness",
  description: "Test description",
  type: FEEDBACK_DEFINITION_TYPE.boolean,
  details: {
    true_label: "Yes",
    false_label: "No",
  },
  created_at: "2024-01-01T00:00:00Z",
  last_updated_at: "2024-01-01T00:00:00Z",
};

describe("AddEditFeedbackDefinitionDialog", () => {
  describe("Clone mode", () => {
    it("should append ' (Copy)' to the name when cloning", () => {
      const setOpen = vi.fn();

      render(
        <AddEditFeedbackDefinitionDialog
          open={true}
          setOpen={setOpen}
          feedbackDefinition={mockBooleanFeedbackDefinition}
          mode="clone"
        />,
      );

      expect(screen.getByText("Clone feedback definition")).toBeInTheDocument();

      const nameInput = screen.getByLabelText("Name") as HTMLInputElement;
      expect(nameInput.value).toBe("Helpfulness (Copy)");
    });

    it("should preserve all other properties when cloning", () => {
      const setOpen = vi.fn();

      render(
        <AddEditFeedbackDefinitionDialog
          open={true}
          setOpen={setOpen}
          feedbackDefinition={mockBooleanFeedbackDefinition}
          mode="clone"
        />,
      );

      const descriptionInput = screen.getByLabelText(
        "Description",
      ) as HTMLTextAreaElement;
      expect(descriptionInput.value).toBe("Test description");

      expect(
        screen.getByText("Create feedback definition"),
      ).toBeInTheDocument();
    });
  });

  describe("Edit mode", () => {
    it("should not append ' (Copy)' to the name when editing", () => {
      const setOpen = vi.fn();

      render(
        <AddEditFeedbackDefinitionDialog
          open={true}
          setOpen={setOpen}
          feedbackDefinition={mockBooleanFeedbackDefinition}
          mode="edit"
        />,
      );

      expect(screen.getByText("Edit feedback definition")).toBeInTheDocument();

      const nameInput = screen.getByLabelText("Name") as HTMLInputElement;
      expect(nameInput.value).toBe("Helpfulness");
    });
  });

  describe("Create mode", () => {
    it("should have empty name when creating new definition", () => {
      const setOpen = vi.fn();

      render(
        <AddEditFeedbackDefinitionDialog
          open={true}
          setOpen={setOpen}
          mode="create"
        />,
      );

      expect(
        screen.getByText("Create a new feedback definition"),
      ).toBeInTheDocument();

      const nameInput = screen.getByLabelText("Name") as HTMLInputElement;
      expect(nameInput.value).toBe("");
    });
  });
});
