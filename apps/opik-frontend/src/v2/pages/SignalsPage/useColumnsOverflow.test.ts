import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
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

let observers: Array<() => void> = [];

class TestResizeObserver {
  constructor(callback: () => void) {
    observers.push(callback);
  }
  observe() {}
  disconnect() {}
}

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
    observers = [];
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
