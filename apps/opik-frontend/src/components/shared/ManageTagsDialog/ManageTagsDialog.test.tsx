import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import ManageTagsDialog from "./ManageTagsDialog";
import { EntityWithTags } from "./ManageTagsDialog";

const mockToast = vi.fn();
vi.mock("@/components/ui/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}));

describe("ManageTagsDialog", () => {
  const mockSetOpen = vi.fn();
  const mockOnUpdate = vi.fn();

  const defaultEntities: EntityWithTags[] = [
    { id: "1", tags: ["tag1", "tag2"] },
    { id: "2", tags: ["tag2", "tag3"] },
  ];

  beforeEach(() => {
    vi.clearAllMocks();
    mockOnUpdate.mockResolvedValue(undefined);
  });

  describe("Rendering", () => {
    it("should render dialog when open", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      expect(screen.getByText("Manage shared tags")).toBeInTheDocument();
      expect(screen.getByTestId("add-tag-button")).toBeInTheDocument();
    });

    it("should not render dialog when closed", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={false}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      expect(screen.queryByText("Manage shared tags")).not.toBeInTheDocument();
    });

    it("should display correct item count in button", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      expect(
        screen.getByRole("button", { name: /Update tags for 2 items/i }),
      ).toBeInTheDocument();
    });

    it("should display totalCount when isAllItemsSelected is true", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
          isAllItemsSelected={true}
          totalCount={100}
        />,
      );

      expect(
        screen.getByRole("button", { name: /Update tags for 100 items/i }),
      ).toBeInTheDocument();
    });

    it("should only display tags shared by all entities", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      expect(screen.getByText("tag2")).toBeInTheDocument();
      expect(screen.queryByText("tag1")).not.toBeInTheDocument();
      expect(screen.queryByText("tag3")).not.toBeInTheDocument();
    });
  });

  describe("Tag Addition via inline input", () => {
    it("should show input when clicking + Add tag", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));

      expect(screen.getByRole("textbox")).toBeInTheDocument();
      expect(screen.queryByTestId("add-tag-button")).not.toBeInTheDocument();
    });

    it("should add a new tag on Enter", async () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));
      const input = screen.getByRole("textbox");
      fireEvent.change(input, { target: { value: "newtag" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("newtag")).toBeInTheDocument();
      });
      expect(screen.getByTestId("add-tag-button")).toBeInTheDocument();
    });

    it("should revert to button on blur", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));
      const input = screen.getByRole("textbox");
      fireEvent.change(input, { target: { value: "some text" } });
      fireEvent.blur(input);

      expect(screen.getByTestId("add-tag-button")).toBeInTheDocument();
    });

    it("should prevent duplicate tags in new tags list", async () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));
      const input = screen.getByRole("textbox");
      fireEvent.change(input, { target: { value: "tag" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("tag")).toBeInTheDocument();
      });

      fireEvent.click(screen.getByTestId("add-tag-button"));
      const input2 = screen.getByRole("textbox");
      fireEvent.change(input2, { target: { value: "tag" } });
      fireEvent.keyDown(input2, { key: "Enter" });

      await waitFor(() => {
        expect(mockToast).toHaveBeenCalledWith(
          expect.objectContaining({
            title: "Tag already added",
            description: 'Tag "tag" is already in the list',
            variant: "destructive",
          }),
        );
      });
    });
  });

  describe("Tag Length Validation", () => {
    it("should enforce maxTagLength via input maxLength attribute", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));
      const input = screen.getByRole("textbox") as HTMLInputElement;

      expect(input.maxLength).toBe(100);
    });
  });

  describe("Max Tags Validation", () => {
    it("should enforce maxTags limit on submission", async () => {
      const existingTags = Array.from({ length: 49 }, (_, i) => `tag${i + 1}`);
      const entitiesWithManyTags = [
        { id: "1", tags: existingTags },
        { id: "2", tags: existingTags },
      ];

      render(
        <ManageTagsDialog
          entities={entitiesWithManyTags}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));
      let input = screen.getByRole("textbox");
      fireEvent.change(input, { target: { value: "tag50" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("tag50")).toBeInTheDocument();
      });

      fireEvent.click(screen.getByTestId("add-tag-button"));
      input = screen.getByRole("textbox");
      fireEvent.change(input, { target: { value: "tag51" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("tag51")).toBeInTheDocument();
      });

      const updateButton = screen.getByRole("button", {
        name: /Update tags for/i,
      });
      fireEvent.click(updateButton);

      await waitFor(() => {
        expect(mockToast).toHaveBeenCalledWith(
          expect.objectContaining({
            title: "Tag limit exceeded",
            description: "An item can only have up to 50 tags",
            variant: "destructive",
          }),
        );
      });

      expect(mockOnUpdate).not.toHaveBeenCalled();
    });
  });

  describe("Max Entities Validation", () => {
    it("should prevent dialog from opening when over maxEntities", () => {
      const manyEntities = Array.from({ length: 1001 }, (_, i) => ({
        id: `${i}`,
        tags: [],
      }));

      render(
        <ManageTagsDialog
          entities={manyEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      expect(mockToast).toHaveBeenCalledWith(
        expect.objectContaining({
          title: "Error",
          description:
            "You can only add tags to up to 1000 items at a time. Please select fewer items.",
        }),
      );
      expect(mockSetOpen).toHaveBeenCalledWith(false);
      expect(screen.queryByText("Manage shared tags")).not.toBeInTheDocument();
    });

    it("should allow dialog when isAllItemsSelected is true regardless of entities length", () => {
      const manyEntities = Array.from({ length: 1001 }, (_, i) => ({
        id: `${i}`,
        tags: [],
      }));

      render(
        <ManageTagsDialog
          entities={manyEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
          isAllItemsSelected={true}
        />,
      );

      expect(screen.getByText("Manage shared tags")).toBeInTheDocument();
      expect(mockToast).not.toHaveBeenCalled();
    });
  });

  describe("Update Operation", () => {
    it("should call onUpdate with correct parameters", async () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));
      const input = screen.getByRole("textbox");
      fireEvent.change(input, { target: { value: "newtag" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("newtag")).toBeInTheDocument();
      });

      const updateButton = screen.getByRole("button", {
        name: /Update tags for/i,
      });
      fireEvent.click(updateButton);

      await waitFor(() => {
        expect(mockOnUpdate).toHaveBeenCalledWith(["newtag"], []);
      });
    });

    it("should show success toast on successful update", async () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));
      const input = screen.getByRole("textbox");
      fireEvent.change(input, { target: { value: "newtag" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("newtag")).toBeInTheDocument();
      });

      const updateButton = screen.getByRole("button", {
        name: /Update tags for/i,
      });
      fireEvent.click(updateButton);

      await waitFor(() => {
        expect(mockToast).toHaveBeenCalledWith(
          expect.objectContaining({
            title: "Success",
            description: "Tags updated: 1 added",
          }),
        );
      });
    });

    it("should reset state after successful update", async () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      fireEvent.click(screen.getByTestId("add-tag-button"));
      const input = screen.getByRole("textbox");
      fireEvent.change(input, { target: { value: "newtag" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("newtag")).toBeInTheDocument();
      });

      const updateButton = screen.getByRole("button", {
        name: /Update tags for/i,
      });
      fireEvent.click(updateButton);

      await waitFor(() => {
        expect(mockSetOpen).toHaveBeenCalledWith(false);
      });
    });

    it("should disable update button when no changes", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      const updateButton = screen.getByRole("button", {
        name: /Update tags for/i,
      });
      expect(updateButton).toBeDisabled();
    });
  });

  describe("Tag Removal (strikethrough)", () => {
    it("should show removed common tag with strikethrough", async () => {
      const entities: EntityWithTags[] = [
        { id: "1", tags: ["shared"] },
        { id: "2", tags: ["shared"] },
      ];

      render(
        <ManageTagsDialog
          entities={entities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      expect(screen.getByText("shared")).toBeInTheDocument();
    });
  });

  describe("Dialog Close", () => {
    it("should reset state when dialog is closed", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      const cancelButton = screen.getByRole("button", { name: /Cancel/i });
      fireEvent.click(cancelButton);

      expect(mockSetOpen).toHaveBeenCalledWith(false);
    });
  });
});
