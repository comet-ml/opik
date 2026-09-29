import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import CompareExperimentsConfigCell, {
  CompareConfig,
} from "./CompareExperimentsConfigCell";
import { ExperimentPromptVersion } from "@/types/datasets";

vi.mock("@/shared/DataTableCells/CellWrapper", () => ({
  default: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock("@/shared/CodeDiff/TextDiff", () => ({
  default: ({ content2 }: { content2: string }) => (
    <span data-testid="text-diff">{content2}</span>
  ),
}));

// Records the props the cell passes, so the link target and active version
// stay asserted without pulling in routing.
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
      data-id={id}
      data-active-version={String(search?.activeVersionId ?? "")}
      data-deleted={String(name === undefined)}
    >
      {name}
    </span>
  ),
}));

const promptVersion = (
  overrides: Partial<ExperimentPromptVersion> = {},
): ExperimentPromptVersion => ({
  id: "pv1",
  prompt_id: "p1",
  prompt_name: "My Prompt",
  commit: "c96aa875",
  version_number: "v1",
  ...overrides,
});

const renderCell = (
  row: CompareConfig,
  experimentId: string,
  onlyDiff = false,
) =>
  render(
    <CompareExperimentsConfigCell
      {...({
        column: {
          id: experimentId,
          columnDef: { meta: { custom: { onlyDiff } } },
        },
        row: { original: row },
        table: { options: { meta: {} } },
      } as unknown as CellContext<CompareConfig, unknown>)}
    />,
  );

const promptRow = (
  versions: Record<string, ExperimentPromptVersion[] | undefined>,
  labels: Record<string, string | undefined>,
): CompareConfig => ({
  name: "Prompt version (linked)",
  base: "e1",
  data: labels,
  promptVersions: versions,
  different: true,
});

describe("CompareExperimentsConfigCell prompt version row", () => {
  const tags = () => screen.queryAllByTestId("prompt-tag");

  it("links each of the experiment's prompt versions", () => {
    renderCell(
      promptRow(
        {
          e1: [
            promptVersion(),
            promptVersion({
              id: "pv2",
              prompt_id: "p2",
              prompt_name: "Guardrail",
              version_number: "v4",
            }),
          ],
        },
        { e1: "Guardrail (v4), My Prompt (v1)" },
      ),
      "e1",
    );

    expect(
      tags().map((t) => [
        t.textContent,
        t.getAttribute("data-id"),
        t.getAttribute("data-active-version"),
      ]),
    ).toEqual([
      ["My Prompt (v1)", "p1", "pv1"],
      ["Guardrail (v4)", "p2", "pv2"],
    ]);
  });

  it("renders a deleted prompt in the tag's deleted state", () => {
    renderCell(
      promptRow(
        {
          e1: [
            promptVersion({
              prompt_name: null,
              commit: null,
              version_number: undefined,
            }),
          ],
        },
        { e1: "Deleted prompt" },
      ),
      "e1",
    );

    expect(tags()[0]).toHaveAttribute("data-deleted", "true");
  });

  it("shows 'No value' for an experiment with no linked prompt", () => {
    renderCell(
      promptRow(
        { e1: [promptVersion()], e2: undefined },
        { e1: "My Prompt (v1)" },
      ),
      "e2",
    );

    expect(tags()).toHaveLength(0);
    expect(screen.getByText("No value")).toBeInTheDocument();
  });

  // The diff view highlights what changed against the baseline, which tags
  // cannot show, so it keeps comparing the text labels.
  it("falls back to the text diff under 'show differences only'", () => {
    renderCell(
      promptRow(
        {
          e1: [promptVersion()],
          e2: [promptVersion({ id: "pv2", version_number: "v2" })],
        },
        { e1: "My Prompt (v1)", e2: "My Prompt (v2)" },
      ),
      "e2",
      true,
    );

    expect(tags()).toHaveLength(0);
    expect(screen.getByTestId("text-diff")).toHaveTextContent("My Prompt (v2)");
  });

  it("renders plain text for rows that carry no prompt versions", () => {
    renderCell(
      {
        name: "model",
        base: "e1",
        data: { e1: "gpt-4o" },
        different: false,
      },
      "e1",
    );

    expect(tags()).toHaveLength(0);
    expect(screen.getByText("gpt-4o")).toBeInTheDocument();
  });
});
