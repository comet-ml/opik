import React from "react";
import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { useForm } from "react-hook-form";

import FeedbackScoreConditions from "./FeedbackScoreConditions";
import { TooltipProvider } from "@/ui/tooltip";
import { Form } from "@/ui/form";
import { ScoreSource } from "@/v2/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";

// The score picker fetches feedback definitions; what this file is about is the chrome around it.
vi.mock(
  "@/v2/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox",
  async (importOriginal) => {
    const actual = await importOriginal<Record<string, unknown>>();
    return {
      ...actual,
      default: () => <div data-testid="score-select" />,
    };
  },
);

type Condition = { name: string; operator: string; threshold: string };

const group = (...names: string[]) => ({
  conditions: names.map<Condition>((name) => ({
    name,
    operator: ">",
    threshold: "0.5",
  })),
});

const Harness: React.FC<{
  groups: ReturnType<typeof group>[];
  singleGroup?: boolean;
}> = ({ groups, singleGroup }) => {
  const form = useForm({ defaultValues: { automation: { groups } } });

  return (
    <TooltipProvider>
      <Form {...form}>
        <FeedbackScoreConditions
          form={form}
          groupsPath="automation.groups"
          scoreSource={ScoreSource.TRACES}
          projectId="project-1"
          singleGroup={singleGroup}
        />
      </Form>
    </TooltipProvider>
  );
};

/**
 * Both features render this component, so its two modes are a contract: annotation queue automation
 * asks for one AND-ed list with no chrome, alerts for OR-ed groups that say so.
 */
describe("FeedbackScoreConditions", () => {
  describe("single-group mode", () => {
    it("reads as a plain list: no group chrome, no AND badges, no way to add a group", () => {
      render(<Harness singleGroup groups={[group("hallucination", "tone")]} />);

      expect(screen.getAllByTestId("score-select")).toHaveLength(2);
      expect(screen.queryByText("Group 1")).not.toBeInTheDocument();
      expect(screen.queryByText("AND")).not.toBeInTheDocument();
      expect(
        screen.queryByRole("button", { name: /add or group/i }),
      ).not.toBeInTheDocument();
    });

    it("still shows everything a queue already has, chrome and OR badges included", () => {
      render(
        <Harness
          singleGroup
          groups={[group("hallucination"), group("relevance")]}
        />,
      );

      expect(screen.getByText("Group 1")).toBeInTheDocument();
      expect(screen.getByText("Group 2")).toBeInTheDocument();
      expect(screen.getByText("OR")).toBeInTheDocument();
      // Adding another group is still refused: the UI offers one group, it only respects what is saved.
      expect(
        screen.queryByRole("button", { name: /add or group/i }),
      ).not.toBeInTheDocument();
    });
  });

  describe("default mode", () => {
    it("offers OR groups and separates conditions with AND, as alerts expect", () => {
      render(<Harness groups={[group("hallucination", "tone")]} />);

      expect(screen.getByText("Group 1")).toBeInTheDocument();
      expect(screen.getByText("AND")).toBeInTheDocument();
      expect(
        screen.getByRole("button", { name: /add or group/i }),
      ).toBeInTheDocument();
    });
  });
});
