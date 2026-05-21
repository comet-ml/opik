import { fireEvent, render, screen } from "@testing-library/react";
import type React from "react";
import { describe, expect, it, vi } from "vitest";

import { TooltipProvider } from "@/ui/tooltip";
import RemovableTag from "./RemovableTag";

const renderRemovableTag = (element: React.ReactElement) => {
  return render(<TooltipProvider>{element}</TooltipProvider>);
};

describe("RemovableTag", () => {
  it("calls onClick when the tag is clicked", () => {
    const onClick = vi.fn();

    renderRemovableTag(
      <RemovableTag label="myprotein-en-gb" onClick={onClick} />,
    );

    fireEvent.click(screen.getByRole("button", { name: "myprotein-en-gb" }));

    expect(onClick).toHaveBeenCalledWith("myprotein-en-gb");
  });

  it("does not call onClick when the remove affordance is clicked", () => {
    const onClick = vi.fn();
    const onDelete = vi.fn();

    renderRemovableTag(
      <RemovableTag
        label="myprotein-en-gb"
        onClick={onClick}
        onDelete={onDelete}
      />,
    );

    fireEvent.click(screen.getByLabelText("Remove myprotein-en-gb"));

    expect(onDelete).toHaveBeenCalledWith("myprotein-en-gb");
    expect(onClick).not.toHaveBeenCalled();
  });
});
