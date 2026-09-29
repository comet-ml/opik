import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";

import ConfigurationTab from "./ConfigurationTab";
import { Experiment, ExperimentPromptVersion } from "@/types/datasets";

// The tab is exercised here for its row assembly, not its table chrome: the
// DataTable, sticky containers and navigation tags all pull in routing and
// virtualization that say nothing about which rows get built.
vi.mock("@/shared/DataTable/DataTable", () => ({
  default: ({ data }: { data: { name: string }[] }) => (
    <div data-testid="rows">
      {data.map((row) => (
        <div key={row.name} data-testid="row">
          {row.name}
        </div>
      ))}
    </div>
  ),
}));

vi.mock("@/shared/PageBodyStickyContainer/PageBodyStickyContainer", () => ({
  default: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock(
  "@/v2/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper",
  () => ({
    default: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  }),
);

// Stands in for the real tag but records the props the tab passes, so the
// link target and active-version query stay asserted rather than erased.
vi.mock("@/shared/NavigationTag", () => ({
  default: ({
    id,
    name,
    search,
  }: {
    id: string;
    name?: string;
    search?: Record<string, unknown>;
  }) => (
    <span
      data-testid="prompt-tag"
      data-deleted={String(name === undefined)}
      data-id={id}
      data-active-version={String(search?.activeVersionId ?? "")}
    >
      {name}
    </span>
  ),
}));

vi.mock(
  "@/v2/pages/CompareExperimentsPage/CompareExperimentsActionsPanel",
  () => ({
    default: () => null,
  }),
);

const queryParams: Record<string, unknown> = {};

vi.mock("use-query-params", () => ({
  StringParam: {},
  BooleanParam: {},
  useQueryParam: (name: string) => [queryParams[name], vi.fn()],
}));

vi.mock("use-local-storage-state", () => ({
  default: () => [{}, vi.fn()],
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

const renderTab = (experiments: Experiment[]) =>
  render(
    <ConfigurationTab
      experimentsIds={experiments.map((e) => e.id)}
      experiments={experiments}
      isPending={false}
    />,
  );

const PROMPT_VERSION_ROW = "Prompt version (linked)";

const rowNames = () => screen.queryAllByTestId("row").map((r) => r.textContent);

describe("ConfigurationTab prompt version row", () => {
  beforeEach(() => {
    Object.keys(queryParams).forEach((key) => delete queryParams[key]);
  });

  it("adds a prompt version row when comparing experiments", () => {
    renderTab([
      experiment("e1", { prompt_versions: [promptVersion()] }),
      experiment("e2", {
        prompt_versions: [promptVersion({ id: "pv2", version_number: "v2" })],
      }),
    ]);

    expect(rowNames()).toContain(PROMPT_VERSION_ROW);
  });

  it("keeps the row when only some experiments have a linked prompt", () => {
    renderTab([
      experiment("e1", { prompt_versions: [promptVersion()] }),
      experiment("e2"),
    ]);

    expect(rowNames()).toContain(PROMPT_VERSION_ROW);
  });

  it("omits the row when no compared experiment has a linked prompt", () => {
    renderTab([experiment("e1"), experiment("e2")]);

    expect(rowNames()).not.toContain(PROMPT_VERSION_ROW);
  });

  it("omits the row outside compare mode, where prompts render as tags", () => {
    renderTab([experiment("e1", { prompt_versions: [promptVersion()] })]);

    expect(rowNames()).not.toContain(PROMPT_VERSION_ROW);
  });

  it("stays alongside a metadata key named Prompt version", () => {
    renderTab([
      experiment("e1", {
        prompt_versions: [promptVersion()],
        metadata: { "Prompt version": "from metadata" },
      }),
      experiment("e2", { prompt_versions: [promptVersion({ id: "pv2" })] }),
    ]);

    expect(rowNames()).toEqual(
      expect.arrayContaining([PROMPT_VERSION_ROW, "Prompt version"]),
    );
  });

  it("hides the row under 'show differences only' when the prompts match", () => {
    queryParams.diff = true;

    renderTab([
      experiment("e1", { prompt_versions: [promptVersion()] }),
      experiment("e2", { prompt_versions: [promptVersion({ id: "pv2" })] }),
    ]);

    expect(rowNames()).not.toContain(PROMPT_VERSION_ROW);
  });

  it("keeps the row under 'show differences only' when the prompts differ", () => {
    queryParams.diff = true;

    renderTab([
      experiment("e1", { prompt_versions: [promptVersion()] }),
      experiment("e2", {
        prompt_versions: [promptVersion({ id: "pv2", version_number: "v9" })],
      }),
    ]);

    expect(rowNames()).toContain(PROMPT_VERSION_ROW);
  });

  it("is filtered out by a search that does not match its name", () => {
    queryParams.searchConfig = "temperature";

    renderTab([
      experiment("e1", { prompt_versions: [promptVersion()] }),
      experiment("e2", { prompt_versions: [promptVersion({ id: "pv2" })] }),
    ]);

    expect(rowNames()).not.toContain(PROMPT_VERSION_ROW);
  });

  it("is kept by a search that matches its name", () => {
    queryParams.searchConfig = "prompt";

    renderTab([
      experiment("e1", { prompt_versions: [promptVersion()] }),
      experiment("e2", { prompt_versions: [promptVersion({ id: "pv2" })] }),
    ]);

    expect(rowNames()).toContain(PROMPT_VERSION_ROW);
  });
});

// These tags are how a single experiment links back to the Prompt Library
// (OPIK_6838), so the link target and the version it opens are part of the
// contract, not incidental rendering.
describe("ConfigurationTab prompt tags", () => {
  beforeEach(() => {
    Object.keys(queryParams).forEach((key) => delete queryParams[key]);
  });

  const tags = () => screen.queryAllByTestId("prompt-tag");

  it("renders one tag per linked prompt, labelled with name and version", () => {
    renderTab([
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

    expect(tags().map((t) => t.textContent)).toEqual([
      "My Prompt (v1)",
      "Guardrail (v4)",
    ]);
  });

  it("links each tag to its prompt and opens that specific version", () => {
    renderTab([experiment("e1", { prompt_versions: [promptVersion()] })]);

    const [tag] = tags();
    expect(tag).toHaveAttribute("data-id", "p1");
    expect(tag).toHaveAttribute("data-active-version", "pv1");
  });

  // Without a name, NavigationTag falls back to its disabled "Deleted prompt"
  // state rather than an enabled blank link.
  it("renders a deleted prompt in the tag's deleted state", () => {
    renderTab([
      experiment("e1", {
        prompt_versions: [
          promptVersion({
            prompt_name: null,
            commit: null,
            version_number: undefined,
          }),
        ],
      }),
    ]);

    expect(tags()[0]).toHaveAttribute("data-deleted", "true");
  });

  it("renders no tags in compare mode, where the row carries the versions", () => {
    renderTab([
      experiment("e1", { prompt_versions: [promptVersion()] }),
      experiment("e2", { prompt_versions: [promptVersion({ id: "pv2" })] }),
    ]);

    expect(tags()).toHaveLength(0);
  });

  it("renders no tags when the experiment has no linked prompt", () => {
    renderTab([experiment("e1")]);

    expect(tags()).toHaveLength(0);
  });
});
