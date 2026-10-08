import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ColumnDef } from "@tanstack/react-table";

import { ROW_HEIGHT } from "@/types/shared";
import StickyScrollTable from "./StickyScrollTable";

type Row = { name: string };

const COLUMNS: ColumnDef<Row, unknown>[] = [
  { id: "name", accessorKey: "name", header: "Name" },
];

const renderTable = (scrollLinked: boolean) => {
  vi.stubGlobal("CSS", { supports: () => scrollLinked });
  render(
    <StickyScrollTable
      columns={COLUMNS}
      data={[{ name: "a" }]}
      rowHeight={ROW_HEIGHT.small}
      resizeConfig={{ enabled: false }}
      noData={null}
      showLoadingOverlay={false}
      testId="table"
    />,
  );
  return {
    header: screen.getByTestId("table-header"),
    body: screen.getByTestId("table-body"),
  };
};

describe("StickyScrollTable", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe("when the browser supports scroll-driven animations", () => {
    it("should move the header from the body's scroll timeline, not by copying scrollLeft", () => {
      const { header, body } = renderTable(true);

      body.scrollLeft = 120;
      fireEvent.scroll(body);

      expect(body).toHaveClass("comet-scroll-linked-source");
      expect(
        header.querySelector(".comet-scroll-linked-follower"),
      ).not.toBeNull();
      expect(header.scrollLeft).toBe(0);
    });

    it("should scroll the body when the user scrolls sideways over the header", () => {
      const { header, body } = renderTable(true);

      fireEvent.wheel(header, { deltaX: 80 });

      expect(body.scrollLeft).toBe(80);
    });
  });

  describe("when the browser lacks scroll-driven animations", () => {
    it("should copy the body's scrollLeft to the header", () => {
      const { header, body } = renderTable(false);

      body.scrollLeft = 120;
      fireEvent.scroll(body);

      expect(header.scrollLeft).toBe(120);
      expect(header.querySelector(".comet-scroll-linked-follower")).toBeNull();
    });

    it("should copy the header's scrollLeft to the body", () => {
      const { header, body } = renderTable(false);

      header.scrollLeft = 90;
      fireEvent.scroll(header);

      expect(body.scrollLeft).toBe(90);
    });
  });
});
