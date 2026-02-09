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

      expect(screen.getByText("Manage tags")).toBeInTheDocument();
      expect(
        screen.getByPlaceholderText("Type a tag and press Enter..."),
      ).toBeInTheDocument();
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

      expect(screen.queryByText("Manage tags")).not.toBeInTheDocument();
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
  });

  describe("Tag Addition", () => {
    it("should add a new tag when input is valid", async () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      );
      fireEvent.change(input, { target: { value: "newtag" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("newtag")).toBeInTheDocument();
      });
    });

    it("should trim whitespace from new tags", async () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      );
      fireEvent.change(input, { target: { value: "  newtag  " } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("newtag")).toBeInTheDocument();
      });
    });

    it("should not add empty tag", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      );

      fireEvent.change(input, { target: { value: "   " } });
      fireEvent.keyDown(input, { key: "Enter" });

      expect(screen.queryByText("No tags added yet")).toBeInTheDocument();
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

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      );

      fireEvent.change(input, { target: { value: "tag" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("tag")).toBeInTheDocument();
      });

      fireEvent.change(input, { target: { value: "tag" } });
      fireEvent.keyDown(input, { key: "Enter" });

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
          maxTagLength={10}
        />,
      );

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      ) as HTMLInputElement;

      expect(input.maxLength).toBe(10);
    });

    it("should use default maxTagLength of 100", () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
        />,
      );

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      ) as HTMLInputElement;

      expect(input.maxLength).toBe(100);
    });
  });

  describe("Max Tags Validation", () => {
    it("should enforce maxTags limit for additions", async () => {
      render(
        <ManageTagsDialog
          entities={defaultEntities}
          open={true}
          setOpen={mockSetOpen}
          onUpdate={mockOnUpdate}
          maxTags={2}
        />,
      );

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      );

      fireEvent.change(input, { target: { value: "newtag1" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("newtag1")).toBeInTheDocument();
      });

      fireEvent.change(input, { target: { value: "newtag2" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(screen.getByText("newtag2")).toBeInTheDocument();
      });

      fireEvent.change(input, { target: { value: "newtag3" } });
      fireEvent.keyDown(input, { key: "Enter" });

      await waitFor(() => {
        expect(mockToast).toHaveBeenCalledWith(
          expect.objectContaining({
            title: "Too many tags",
            description: "You can only add up to 2 tags at once",
          }),
        );
      });

      expect(screen.queryByText("newtag3")).not.toBeInTheDocument();
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
          maxEntities={1000}
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
      expect(screen.queryByText("Manage tags")).not.toBeInTheDocument();
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
          maxEntities={1000}
          isAllItemsSelected={true}
        />,
      );

      expect(screen.getByText("Manage tags")).toBeInTheDocument();
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

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      );
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

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      );
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

      const input = screen.getByPlaceholderText(
        "Type a tag and press Enter...",
      );
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
