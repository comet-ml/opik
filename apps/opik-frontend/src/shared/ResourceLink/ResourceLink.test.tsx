import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import ResourceLink, { RESOURCE_TYPE } from "./ResourceLink";

vi.mock("@tanstack/react-router", () => ({
  Link: ({
    to,
    params,
    children,
  }: {
    to: string;
    params: Record<string, string>;
    children: React.ReactNode;
  }) => (
    <a data-testid="link" data-to={to} data-params={JSON.stringify(params)}>
      {children}
    </a>
  ),
}));

vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "ws" }),
  useActiveProjectId: () => "active-project",
}));

const renderedParams = () =>
  JSON.parse(screen.getByTestId("link").dataset.params ?? "{}");

describe("ResourceLink", () => {
  it("links into the active project by default", () => {
    render(
      <ResourceLink
        resource={RESOURCE_TYPE.experiment}
        id="dataset-1"
        name="exp"
      />,
    );

    expect(renderedParams()).toEqual({
      workspaceName: "ws",
      projectId: "active-project",
      datasetId: "dataset-1",
    });
  });

  it("keeps an explicit projectId over the active project", () => {
    render(
      <ResourceLink
        resource={RESOURCE_TYPE.experiment}
        id="dataset-1"
        name="exp"
        params={{ projectId: "experiment-project" }}
      />,
    );

    expect(renderedParams()).toEqual({
      workspaceName: "ws",
      projectId: "experiment-project",
      datasetId: "dataset-1",
    });
  });
});
