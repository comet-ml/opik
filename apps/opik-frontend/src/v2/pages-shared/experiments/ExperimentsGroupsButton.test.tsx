import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import GroupsButton from "@/shared/GroupsButton/GroupsButton";
import {
  DEFAULT_GROUPS,
  FILTER_AND_GROUP_COLUMNS,
} from "./useExperimentsGroupsAndFilters";

describe("Experiments GroupsButton", () => {
  it("groups by item source by default, shown in the button label", () => {
    render(
      <GroupsButton
        columns={FILTER_AND_GROUP_COLUMNS}
        groups={DEFAULT_GROUPS}
        onChange={vi.fn()}
      />,
    );

    expect(
      screen.getByRole("button", { name: "Groups (1)" }),
    ).toBeInTheDocument();
  });

  it("keeps the label when no groups are active", () => {
    render(
      <GroupsButton
        columns={FILTER_AND_GROUP_COLUMNS}
        groups={[]}
        onChange={vi.fn()}
      />,
    );

    expect(
      screen.getByRole("button", { name: "Groups (0)" }),
    ).toBeInTheDocument();
  });
});
