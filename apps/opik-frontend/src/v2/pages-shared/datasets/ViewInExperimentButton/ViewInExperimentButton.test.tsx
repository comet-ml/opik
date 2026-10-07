import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import ViewInExperimentButton from "./ViewInExperimentButton";

const navigate = vi.fn();
let fromParam: string | undefined;
let experimentNames: Record<string, string> = {};

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => navigate,
  useRouter: () => ({
    basepath: "/",
    options: {
      parseSearch: (searchStr: string) =>
        Object.fromEntries(
          [...new URLSearchParams(searchStr)].map(([key, value]) => {
            try {
              return [key, JSON.parse(value)];
            } catch {
              return [key, value];
            }
          }),
        ),
    },
  }),
}));

vi.mock("use-query-params", () => ({
  StringParam: {},
  useQueryParam: () => [fromParam, vi.fn()],
}));

const experimentsByIdsMock = vi.fn(
  ({ experimentsIds }: { experimentsIds: string[] }) =>
    experimentsIds.map((id) => ({
      data: experimentNames[id] ? { name: experimentNames[id] } : undefined,
    })),
);

vi.mock("@/api/datasets/useExperimenstByIds", () => ({
  default: (params: { experimentsIds: string[] }) =>
    experimentsByIdsMock(params),
}));

const COMPARE_PATH = "/ws/projects/p1/experiments/d1/compare";
const buildFrom = (experiments: unknown[], row = "item-1") =>
  `${COMPARE_PATH}?experiments=${encodeURIComponent(
    JSON.stringify(experiments),
  )}&row=${row}&search=foo`;

const renderButton = (
  datasetItemId = "item-2",
  permissions = DEFAULT_PERMISSIONS,
) =>
  render(
    <PermissionsProvider value={permissions}>
      <TooltipProvider>
        <ViewInExperimentButton datasetItemId={datasetItemId} />
      </TooltipProvider>
    </PermissionsProvider>,
  );

describe("ViewInExperimentButton", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fromParam = undefined;
    experimentNames = {};
  });

  it("renders nothing without a from param", () => {
    renderButton();

    expect(screen.queryByText("Experiment")).toBeNull();
  });

  it("renders nothing when from is not an experiment compare URL", () => {
    fromParam = "/ws/projects/p1/test-suites/s1/items";
    renderButton();

    expect(screen.queryByText("Experiment")).toBeNull();
  });

  it("renders nothing without experiment view permission", () => {
    fromParam = buildFrom(["exp-1"]);
    renderButton("item-2", {
      ...DEFAULT_PERMISSIONS,
      permissions: {
        ...DEFAULT_PERMISSIONS.permissions,
        canViewExperiments: false,
      },
    });

    expect(screen.queryByText("Experiment")).toBeNull();
    expect(experimentsByIdsMock).not.toHaveBeenCalledWith({
      experimentsIds: ["exp-1"],
    });
  });

  it.each([
    ["a non-string id", ["exp-1", 42]],
    ["an empty id", ["exp-1", ""]],
    ["a null id", [null]],
  ])("renders nothing and fetches nothing for %s", (_, experiments) => {
    fromParam = buildFrom(experiments);
    renderButton();

    expect(screen.queryByText("Experiment")).toBeNull();
    expect(experimentsByIdsMock).toHaveBeenCalledWith({ experimentsIds: [] });
    expect(experimentsByIdsMock).not.toHaveBeenCalledWith(
      expect.objectContaining({
        experimentsIds: expect.arrayContaining([expect.anything()]),
      }),
    );
  });

  it("opens the originating experiment view on the current item", () => {
    fromParam = buildFrom(["exp-1"]);
    experimentNames = { "exp-1": "baseline" };
    renderButton("item-2");

    fireEvent.click(screen.getByText("Experiment"));

    expect(navigate).toHaveBeenCalledWith({
      to: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
      params: { workspaceName: "ws", projectId: "p1", datasetId: "d1" },
      search: { experiments: ["exp-1"], row: "item-2", search: "foo" },
    });
  });

  it("names every compared experiment in the tooltip", async () => {
    fromParam = buildFrom(["exp-1", "exp-2"]);
    experimentNames = { "exp-1": "baseline", "exp-2": "candidate" };
    renderButton();

    fireEvent.focus(screen.getByText("Experiment").closest("button")!);

    expect(
      (
        await screen.findAllByText(
          "View this item in experiments: baseline, candidate",
        )
      ).length,
    ).toBeGreaterThan(0);
  });
});
