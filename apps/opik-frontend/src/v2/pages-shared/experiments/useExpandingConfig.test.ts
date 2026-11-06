import { describe, it, expect } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { StrictMode } from "react";
import { ExpandedState } from "@tanstack/react-table";

import { Groups } from "@/types/groups";
import { SORT_DIRECTION } from "@/types/sorting";
import { useExpandingConfig } from "./useExpandingConfig";

const GROUPS: Groups = [
  { id: "g1", field: "f1", direction: SORT_DIRECTION.ASC, type: "" },
  { id: "g2", field: "f2", direction: SORT_DIRECTION.ASC, type: "" },
];

const expand =
  (...ids: string[]) =>
  (prev: ExpandedState): ExpandedState => ({
    ...(prev as Record<string, boolean>),
    ...Object.fromEntries(ids.map((id) => [id, true])),
  });

describe("useExpandingConfig", () => {
  it("keeps only the most recently expanded deepest group when React replays the updater", () => {
    // Two queued updates in one act make StrictMode replay the second updater during render.
    const { result } = renderHook(
      () => useExpandingConfig({ groups: GROUPS, maxExpandedDeepestGroups: 1 }),
      { wrapper: StrictMode },
    );

    act(() => {
      result.current.setExpanded(expand("x", "x>a"));
      result.current.setExpanded(expand("x>b"));
    });

    expect(result.current.expanded).toEqual({
      x: true,
      "x>a": false,
      "x>b": true,
    });
  });
});
