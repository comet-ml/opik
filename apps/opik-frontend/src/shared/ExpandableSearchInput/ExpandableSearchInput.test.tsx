import React from "react";
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import ExpandableSearchInput from "./ExpandableSearchInput";
import { TooltipProvider } from "@/ui/tooltip";

type Props = React.ComponentProps<typeof ExpandableSearchInput>;

const renderSearch = (props: Props) => {
  const ui = (next: Props) => (
    <TooltipProvider>
      <ExpandableSearchInput placeholder="Search messages" {...next} />
    </TooltipProvider>
  );
  const result = render(ui(props));
  return {
    ...result,
    rerenderSearch: (next: Props) => result.rerender(ui(next)),
  };
};

const searchInput = () => screen.queryByPlaceholderText("Search messages");

describe("ExpandableSearchInput", () => {
  it("opens without taking focus when it mounts with a value", () => {
    renderSearch({ value: "refund" });

    expect(searchInput()).toHaveValue("refund");
    expect(searchInput()).not.toHaveFocus();
  });

  it("takes focus when the user opens it", () => {
    renderSearch({});

    fireEvent.click(screen.getByRole("button"));

    expect(searchInput()).toHaveFocus();
  });

  // A search restored from the URL while data loads must not filter with no visible search.
  it("opens a value it mounted with once it stops being disabled", () => {
    const onExpandedChange = vi.fn();
    const { rerenderSearch } = renderSearch({
      value: "refund",
      disabled: true,
      onExpandedChange,
    });

    expect(searchInput()).not.toBeInTheDocument();

    rerenderSearch({ value: "refund", disabled: false, onExpandedChange });

    expect(searchInput()).toHaveValue("refund");
    expect(searchInput()).not.toHaveFocus();
    expect(onExpandedChange).toHaveBeenCalledWith(true);
  });

  it("stays closed if the value was cleared before it stopped being disabled", () => {
    const { rerenderSearch } = renderSearch({
      value: "refund",
      disabled: true,
    });

    rerenderSearch({ value: "", disabled: true });
    rerenderSearch({ value: "", disabled: false });

    expect(searchInput()).not.toBeInTheDocument();
  });

  // Dataset items keeps its search applied when the user collapses the input, so a value that
  // is still present must not re-open it.
  it("stays closed after the user collapses it, even if the value is kept", () => {
    const { rerenderSearch } = renderSearch({ value: "refund" });

    fireEvent.keyDown(searchInput()!, { key: "Escape" });
    rerenderSearch({ value: "refund", disabled: true });
    rerenderSearch({ value: "refund", disabled: false });

    expect(searchInput()).not.toBeInTheDocument();
  });
});
