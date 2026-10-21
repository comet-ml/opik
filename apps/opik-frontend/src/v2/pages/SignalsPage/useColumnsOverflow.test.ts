import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import React from "react";

import useColumnsOverflow from "./useColumnsOverflow";
import { ISSUES_LIST_ATTRIBUTE } from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";
import { AgentInsightsIssuesPage } from "@/types/signals";

const CONTAINER_HEIGHT = 500;
const COLUMNS_TOP = 100;

// happy-dom has no layout engine, so the three measurements the hook reads are stubbed.
const stub = (element: HTMLElement, prop: string, value: unknown) =>
  Object.defineProperty(element, prop, {
    configurable: true,
    get: () => value,
  });

// happy-dom has no ResizeObserver: this one keeps the hook's callback so a test can fire it the way
// a real resize would, and records teardown.
const resizeCallbacks: Array<() => void> = [];
let disconnectCount = 0;

class TestResizeObserver {
  constructor(private callback: () => void) {
    resizeCallbacks.push(callback);
  }
  observe() {}
  disconnect() {
    disconnectCount += 1;
  }
}

const fireResize = () => resizeCallbacks.forEach((callback) => callback());

const buildDom = () => {
  const container = document.createElement("div");
  const columns = document.createElement("div");
  container.appendChild(columns);
  document.body.appendChild(container);

  stub(container, "clientHeight", CONTAINER_HEIGHT);
  stub(columns, "offsetParent", container);
  stub(columns, "offsetTop", COLUMNS_TOP);

  return { container, columns };
};

const mountList = (columns: HTMLElement, scrollHeight: number) => {
  const list = document.createElement("div");
  list.setAttribute(ISSUES_LIST_ATTRIBUTE, "");
  stub(list, "scrollHeight", scrollHeight);
  columns.appendChild(list);
  return list;
};

const issuesPage = (total: number) =>
  ({ content: [], total, page: 1, size: 100 }) as unknown as
    | AgentInsightsIssuesPage
    | undefined;

describe("useColumnsOverflow", () => {
  beforeEach(() => {
    resizeCallbacks.length = 0;
    disconnectCount = 0;
    vi.stubGlobal("ResizeObserver", TestResizeObserver);
  });

  afterEach(() => {
    document.body.innerHTML = "";
    vi.unstubAllGlobals();
  });

  it("reports overflow for a list taller than the space the columns get", () => {
    const { columns } = buildDom();
    mountList(columns, 1000);
    const ref = { current: columns } as React.RefObject<HTMLDivElement>;

    const { result } = renderHook(() =>
      useColumnsOverflow(ref, issuesPage(30)),
    );

    expect(result.current).toBe(true);
  });

  it("reports no overflow for a list that fits", () => {
    const { columns } = buildDom();
    mountList(columns, 200);
    const ref = { current: columns } as React.RefObject<HTMLDivElement>;

    const { result } = renderHook(() => useColumnsOverflow(ref, issuesPage(2)));

    expect(result.current).toBe(false);
  });

  it("treats a list that exactly fills the space as fitting", () => {
    // CONTAINER_HEIGHT - COLUMNS_TOP - STUCK_GAP_PX, so this pins the comparison as strictly greater.
    const { columns } = buildDom();
    mountList(columns, CONTAINER_HEIGHT - COLUMNS_TOP - 8);
    const ref = { current: columns } as React.RefObject<HTMLDivElement>;

    const { result } = renderHook(() =>
      useColumnsOverflow(ref, issuesPage(12)),
    );

    expect(result.current).toBe(false);
  });

  it("re-measures when the container resizes", () => {
    const { container, columns } = buildDom();
    mountList(columns, 300);
    const ref = { current: columns } as React.RefObject<HTMLDivElement>;

    const { result } = renderHook(() => useColumnsOverflow(ref, issuesPage(8)));
    expect(result.current).toBe(false);

    stub(container, "clientHeight", 200);
    act(() => fireResize());

    expect(result.current).toBe(true);
  });

  it("stops observing when unmounted", () => {
    const { columns } = buildDom();
    mountList(columns, 1000);
    const ref = { current: columns } as React.RefObject<HTMLDivElement>;

    const { unmount } = renderHook(() =>
      useColumnsOverflow(ref, issuesPage(30)),
    );
    const beforeUnmount = disconnectCount;
    unmount();

    expect(disconnectCount).toBe(beforeUnmount + 1);
  });

  it("measures a list that mounts after the parent's issues query settled", async () => {
    // IssuesTab renders the list from its own query, so it can appear without the page's
    // issues data changing identity — the hook must still notice it.
    const { columns } = buildDom();
    const ref = { current: columns } as React.RefObject<HTMLDivElement>;
    const issues = issuesPage(30);

    const { result } = renderHook(() => useColumnsOverflow(ref, issues));
    expect(result.current).toBe(false);

    mountList(columns, 1000);

    await waitFor(() => expect(result.current).toBe(true));
  });
});
