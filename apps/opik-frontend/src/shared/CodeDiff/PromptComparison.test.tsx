import { describe, it, expect } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

import PromptComparison from "./PromptComparison";
import { PromptComparisonTarget } from "./promptComparisonTargets";

const current = [
  { role: "system", content: "current system text" },
  { role: "user", content: "hello" },
];

const baselineTarget: PromptComparisonTarget = {
  id: "base",
  label: "Baseline",
  prompt: [{ role: "system", content: "baseline system text" }],
};

const parentTarget: PromptComparisonTarget = {
  id: "p-1",
  label: "Parent",
  prompt: [{ role: "system", content: "parent system text" }],
};

describe("PromptComparison", () => {
  describe("without targets", () => {
    it("shows the current prompt as role cards with no compare control", () => {
      const { container } = render(
        <PromptComparison current={current} targets={[]} />,
      );

      expect(container.textContent).toContain("current system text");
      expect(container.textContent).toContain("hello");
      expect(screen.queryByText("Hide diff")).not.toBeInTheDocument();
    });

    it("renders nothing when the current prompt is empty", () => {
      const { container } = render(
        <PromptComparison current={null} targets={[]} />,
      );

      expect(container).toBeEmptyDOMElement();
    });
  });

  describe("with targets", () => {
    it("renders the compare control and diffs against the target", () => {
      const { container } = render(
        <PromptComparison current={current} targets={[baselineTarget]} />,
      );

      // Trigger shows the selected target; current side labelled after the arrow.
      expect(screen.getByText("Baseline")).toBeInTheDocument();
      expect(screen.getByText("→ Trial")).toBeInTheDocument();
      expect(screen.getByText("Hide diff")).toBeInTheDocument();

      // Both sides of the diff render (target removed, current added).
      expect(container.textContent).toContain("baseline system text");
      expect(container.textContent).toContain("current system text");
    });

    it("hides the diff and shows only the current prompt when toggled", () => {
      const { container } = render(
        <PromptComparison current={current} targets={[baselineTarget]} />,
      );

      fireEvent.click(screen.getByText("Hide diff"));

      expect(screen.getByText("Show diff")).toBeInTheDocument();
      expect(container.textContent).toContain("current system text");
      expect(container.textContent).not.toContain("baseline system text");
    });

    it("honors defaultTargetId", () => {
      render(
        <PromptComparison
          current={current}
          targets={[baselineTarget, parentTarget]}
          defaultTargetId="p-1"
        />,
      );

      expect(screen.getByText("Parent")).toBeInTheDocument();
      expect(screen.queryByText("Baseline")).not.toBeInTheDocument();
    });

    it("uses a custom currentLabel", () => {
      render(
        <PromptComparison
          current={current}
          currentLabel="Best trial"
          targets={[baselineTarget]}
        />,
      );

      expect(screen.getByText("→ Best trial")).toBeInTheDocument();
    });
  });
});
