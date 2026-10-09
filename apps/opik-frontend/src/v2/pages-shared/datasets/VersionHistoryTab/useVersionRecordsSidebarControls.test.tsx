import React from "react";
import { beforeEach, describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";

import { DatasetVersion } from "@/types/datasets";
import { useVersionRecordsSidebarControls } from "./useVersionRecordsSidebarControls";

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <QueryParamProvider adapter={WindowHistoryAdapter}>
    {children}
  </QueryParamProvider>
);

const setUrl = (search: string) =>
  window.history.replaceState({}, "", `/items${search}`);

const readParams = () => new URLSearchParams(window.location.search);

describe("useVersionRecordsSidebarControls", () => {
  beforeEach(() => setUrl(""));

  it("opens a version by its hash", () => {
    setUrl("?tab=version-history");
    const { result, rerender } = renderHook(
      () => useVersionRecordsSidebarControls(),
      { wrapper },
    );

    act(() =>
      result.current.openVersion({ version_hash: "abc123" } as DatasetVersion),
    );
    rerender();

    expect(readParams().get("dvs_version")).toBe("abc123");
    expect(readParams().get("tab")).toBe("version-history");
    expect(result.current.versionHash).toBe("abc123");
  });

  it("clears every sheet param on close and keeps the others", () => {
    setUrl(
      "?tab=version-history&search=keep&dvs_version=abc123&dvs_row=r1&dvs_page=2&dvs_search=refund&dvs_filters=%5B%5D&dvs_size=25&dvs_height=large",
    );
    const { result, rerender } = renderHook(
      () => useVersionRecordsSidebarControls(),
      { wrapper },
    );

    act(() => result.current.closeVersion());
    rerender();

    const params = readParams();
    expect([...params.keys()].filter((key) => key.startsWith("dvs_"))).toEqual(
      [],
    );
    expect(params.get("tab")).toBe("version-history");
    expect(params.get("search")).toBe("keep");
    expect(result.current.versionHash).toBeUndefined();
  });
});
