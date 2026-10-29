import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import CompareExperimentsDetails from "./CompareExperimentsDetails";
import { Experiment, ExperimentPromptVersion } from "@/types/datasets";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";

// Records the props each tag receives, so the link target and the version it
// opens stay asserted rather than erased by routing.
vi.mock("@/shared/NavigationTag", () => ({
  default: ({
    id,
    name,
    resource,
    search,
    suffix,
  }: {
    id: string;
    name?: string;
    resource: RESOURCE_TYPE;
    search?: Record<string, unknown>;
    suffix?: React.ReactNode;
  }) => (
    <span
      data-testid={`tag-${resource}`}
      data-deleted={String(name === undefined)}
      data-id={id}
      data-active-version={String(search?.activeVersionId ?? "")}
    >
      <span data-testid="tag-name">{name}</span>
      {suffix}
    </span>
  ),
}));

vi.mock("@/shared/BackButton/BackButton", () => ({ default: () => null }));
vi.mock("@/shared/DateTag/DateTag", () => ({ default: () => null }));
vi.mock("@/shared/ExperimentTag/ExperimentTag", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/FeedbackScoresList/FeedbackScoresList", () => ({
  default: () => null,
}));
vi.mock(
  "@/v2/pages/CompareExperimentsPage/CompareExperimentsButton/CompareExperimentsButton",
  () => ({ default: () => null }),
);
vi.mock("@/v2/pages/CompareExperimentsPage/ExperimentTagsList", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages/CompareExperimentsPage/ExperimentIdButton", () => ({
  default: () => null,
}));

const promptVersion = (
  overrides: Partial<ExperimentPromptVersion> = {},
): ExperimentPromptVersion =>
  ({
    id: "pv1",
    prompt_id: "p1",
    prompt_name: "My Prompt",
    commit: "c96aa875",
    version_number: "v1",
    ...overrides,
  }) as ExperimentPromptVersion;

const experiment = (id: string, overrides: Partial<Experiment> = {}) =>
  ({
    id,
    name: `Experiment ${id}`,
    dataset_id: "d1",
    dataset_name: "geography_questions",
    ...overrides,
  }) as Experiment;

const renderDetails = (experiments: Experiment[]) =>
  render(
    <CompareExperimentsDetails
      experimentsIds={experiments.map((e) => e.id)}
      experiments={experiments}
    />,
  );

const promptTags = () => screen.queryAllByTestId(`tag-${RESOURCE_TYPE.prompt}`);

// The header is the only place a single experiment shows its linked prompts
// and links back to the Prompt Library (OPIK_6838).
describe("CompareExperimentsDetails prompt tags", () => {
  it("renders one tag per linked prompt, each with its version", () => {
    renderDetails([
      experiment("e1", {
        prompt_versions: [
          promptVersion(),
          promptVersion({
            id: "pv2",
            prompt_id: "p2",
            prompt_name: "Guardrail",
            version_number: "v4",
          }),
        ],
      }),
    ]);

    expect(promptTags().map((t) => t.textContent)).toEqual([
      "My Promptv1",
      "Guardrailv4",
    ]);
  });

  it("links each tag to its prompt and opens that specific version", () => {
    renderDetails([experiment("e1", { prompt_versions: [promptVersion()] })]);

    const [tag] = promptTags();
    expect(tag).toHaveAttribute("data-id", "p1");
    expect(tag).toHaveAttribute("data-active-version", "pv1");
  });

  // Without a name, NavigationTag falls back to its disabled "Deleted prompt"
  // state rather than an enabled blank link.
  it("renders a deleted prompt in the tag's deleted state", () => {
    renderDetails([
      experiment("e1", {
        prompt_versions: [{ id: "pv1", prompt_id: "p1" }],
      }),
    ]);

    expect(promptTags()[0]).toHaveAttribute("data-deleted", "true");
  });

  it("renders no tags in compare mode, where the Configuration row carries the versions", () => {
    renderDetails([
      experiment("e1", { prompt_versions: [promptVersion()] }),
      experiment("e2", { prompt_versions: [promptVersion({ id: "pv2" })] }),
    ]);

    expect(promptTags()).toHaveLength(0);
  });

  it("renders no tags when the experiment has no linked prompt", () => {
    renderDetails([experiment("e1")]);

    expect(promptTags()).toHaveLength(0);
  });
});
