import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";

import PromptComparison from "./PromptComparison";
import { PromptComparisonTarget } from "./promptComparisonTargets";

const baselineTarget: PromptComparisonTarget = {
  id: "base",
  label: "Baseline",
  prompt: "alpha baseline prompt",
};

const parentTarget: PromptComparisonTarget = {
  id: "p-1",
  label: "Parent (Trial #3)",
  prompt: "parent prompt",
};

describe("PromptComparison", () => {
  it("renders nothing when there are no targets", () => {
    const { container } = render(
      <PromptComparison current="bravo current prompt" targets={[]} />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  describe("single target", () => {
    it("shows the target label as static text instead of a selector", () => {
      render(
        <PromptComparison
          current="bravo current prompt"
          targets={[baselineTarget]}
        />,
      );

      expect(screen.getByText("Compare against:")).toBeInTheDocument();
      expect(screen.getByText("Baseline")).toBeInTheDocument();
      expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    });

    it("renders the diff against the target prompt", () => {
      const { container } = render(
        <PromptComparison
          current="bravo current prompt"
          targets={[baselineTarget]}
        />,
      );

      // String prompts fall through to the text diff, which renders both sides.
      expect(container.textContent).toContain("bravo");
      expect(container.textContent).toContain("baseline");
    });
  });

  describe("multiple targets", () => {
    it("renders a selector to choose the comparison target", () => {
      render(
        <PromptComparison
          current="bravo current prompt"
          targets={[baselineTarget, parentTarget]}
        />,
      );

      expect(screen.getByRole("combobox")).toBeInTheDocument();
    });

    it("defaults to the first target when defaultTargetId is not provided", () => {
      render(
        <PromptComparison
          current="bravo current prompt"
          targets={[baselineTarget, parentTarget]}
        />,
      );

      // Radix renders the selected item's text inside the trigger.
      expect(screen.getByRole("combobox")).toHaveTextContent("Baseline");
    });

    it("honors defaultTargetId when it matches a target", () => {
      render(
        <PromptComparison
          current="bravo current prompt"
          targets={[baselineTarget, parentTarget]}
          defaultTargetId="p-1"
        />,
      );

      expect(screen.getByRole("combobox")).toHaveTextContent(
        "Parent (Trial #3)",
      );
    });
  });
});
