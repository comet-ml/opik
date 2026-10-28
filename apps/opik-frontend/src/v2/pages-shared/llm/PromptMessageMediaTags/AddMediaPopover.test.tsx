import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import AddMediaPopover, { AddMediaPopoverProps } from "./AddMediaPopover";

const mockToast = vi.fn();
vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}));

const IMAGE_URL = "https://example.com/cat.png";
const AUDIO_URL = "https://example.com/sound.mp3";

const renderOpenPopover = (props: Partial<AddMediaPopoverProps> = {}) => {
  const setItems = vi.fn();
  const onOpenChange = vi.fn();
  render(
    <TooltipProvider>
      <AddMediaPopover
        type="image"
        items={[]}
        setItems={setItems}
        onOpenChange={onOpenChange}
        promptVariables={["image_url", "question"]}
        {...props}
      >
        <button type="button">open</button>
      </AddMediaPopover>
    </TooltipProvider>,
  );
  fireEvent.click(screen.getByText("open"));
  return { setItems, onOpenChange };
};

describe("AddMediaPopover", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("adds the variable as a media item and closes when a variable is clicked", () => {
    const { setItems, onOpenChange } = renderOpenPopover({
      items: [IMAGE_URL],
    });

    fireEvent.click(screen.getByText("{{image_url}}"));

    expect(setItems).toHaveBeenCalledWith([IMAGE_URL, "{{image_url}}"]);
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    expect(screen.queryByText("Add image")).not.toBeInTheDocument();
  });

  it("does not add a variable that is already in the list", () => {
    const { setItems, onOpenChange } = renderOpenPopover({
      items: ["{{image_url}}"],
    });

    fireEvent.click(screen.getByText("{{image_url}}"));

    expect(setItems).not.toHaveBeenCalled();
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ description: "This image already exists" }),
    );
    expect(onOpenChange).not.toHaveBeenCalledWith(false);
  });

  it("does not add a variable once the maximum is reached", () => {
    const { setItems } = renderOpenPopover({
      type: "video",
      items: ["{{question}}"],
      maxItems: 1,
    });

    fireEvent.click(screen.getByText("{{image_url}}"));

    expect(setItems).not.toHaveBeenCalled();
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Maximum limit reached" }),
    );
  });

  it("still adds a typed URL with the Add button", () => {
    const { setItems } = renderOpenPopover({ type: "audio" });

    fireEvent.change(
      screen.getByPlaceholderText("Enter audio URL or template variable"),
      { target: { value: ` ${AUDIO_URL} ` } },
    );
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(setItems).toHaveBeenCalledWith([AUDIO_URL]);
  });

  it("rejects a typed value that is not a URL or a variable", () => {
    const { setItems } = renderOpenPopover();

    fireEvent.change(
      screen.getByPlaceholderText("Enter image URL or template variable"),
      { target: { value: "not a url" } },
    );
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(setItems).not.toHaveBeenCalled();
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Invalid URL" }),
    );
  });
});
