import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

import PermissionsTestProvider from "@/testing/PermissionsTestProvider";
import CreateEntityMenu from "./CreateEntityMenu";

const openMenu = (canEditDatasets: boolean) => {
  render(
    <PermissionsTestProvider permissions={{ canEditDatasets }}>
      <CreateEntityMenu onSelect={vi.fn()}>
        <button type="button">Create</button>
      </CreateEntityMenu>
    </PermissionsTestProvider>,
  );
  fireEvent.keyDown(screen.getByRole("button", { name: "Create" }), {
    key: "Enter",
  });
};

describe("CreateEntityMenu", () => {
  it("offers file upload and SDK with dataset edit permission", () => {
    openMenu(true);

    expect(screen.getByText("Upload a file")).toBeInTheDocument();
    expect(screen.getByText("Use SDK")).toBeInTheDocument();
  });

  it("offers only SDK without dataset edit permission", () => {
    openMenu(false);

    expect(screen.queryByText("Upload a file")).toBeNull();
    expect(screen.getByText("Use SDK")).toBeInTheDocument();
  });
});
