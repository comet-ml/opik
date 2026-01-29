import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { Row } from "@tanstack/react-table";
import DataTable from "./DataTable";

interface TestData {
  id: string;
  name: string;
}

const mockData: TestData[] = [
  { id: "1", name: "Item 1" },
  { id: "2", name: "Item 2" },
  { id: "3", name: "Item 3" },
];

const mockColumns = [
  {
    accessorKey: "id",
    header: "ID",
  },
  {
    accessorKey: "name",
    header: "Name",
  },
];

describe("DataTable with actionsConfig", () => {
  const mockActionsRender = vi.fn((row: Row<TestData>) => (
    <button data-testid={`action-${row.original.id}`}>Action</button>
  ));

  beforeEach(() => {
    mockActionsRender.mockClear();
  });

  it("should not show actions overlay when no row is hovered", () => {
    render(
      <DataTable
        columns={mockColumns}
        data={mockData}
        actionsConfig={{
          render: mockActionsRender,
        }}
      />,
    );

    expect(screen.queryByTestId("action-1")).not.toBeInTheDocument();
    expect(screen.queryByTestId("action-2")).not.toBeInTheDocument();
    expect(screen.queryByTestId("action-3")).not.toBeInTheDocument();
  });

  it("should show actions overlay when row is hovered", async () => {
    render(
      <DataTable
        columns={mockColumns}
        data={mockData}
        getRowId={(row) => row.id}
        actionsConfig={{
          render: mockActionsRender,
        }}
      />,
    );

    const firstRow = screen.getByText("Item 1").closest("tr");
    expect(firstRow).toBeInTheDocument();

    if (firstRow) {
      fireEvent.mouseEnter(firstRow);

      await waitFor(() => {
        expect(screen.getByTestId("action-1")).toBeInTheDocument();
      });
    }
  });

  it("should hide actions overlay when mouse leaves row", async () => {
    render(
      <DataTable
        columns={mockColumns}
        data={mockData}
        getRowId={(row) => row.id}
        actionsConfig={{
          render: mockActionsRender,
        }}
      />,
    );

    const firstRow = screen.getByText("Item 1").closest("tr");

    if (firstRow) {
      fireEvent.mouseEnter(firstRow);

      await waitFor(() => {
        expect(screen.getByTestId("action-1")).toBeInTheDocument();
      });

      fireEvent.mouseLeave(firstRow);

      await waitFor(
        () => {
          expect(screen.queryByTestId("action-1")).not.toBeInTheDocument();
        },
        { timeout: 200 },
      );
    }
  });

  it("should keep actions visible when moving to actions overlay", async () => {
    render(
      <DataTable
        columns={mockColumns}
        data={mockData}
        getRowId={(row) => row.id}
        actionsConfig={{
          render: mockActionsRender,
        }}
      />,
    );

    const firstRow = screen.getByText("Item 1").closest("tr");

    if (firstRow) {
      fireEvent.mouseEnter(firstRow);

      await waitFor(() => {
        expect(screen.getByTestId("action-1")).toBeInTheDocument();
      });

      const actionsButton = screen.getByTestId("action-1");
      fireEvent.mouseLeave(firstRow);
      fireEvent.mouseEnter(actionsButton.parentElement!);

      await waitFor(() => {
        expect(screen.getByTestId("action-1")).toBeInTheDocument();
      });
    }
  });

  it("should render actions for correct row when hovering different rows", async () => {
    render(
      <DataTable
        columns={mockColumns}
        data={mockData}
        getRowId={(row) => row.id}
        actionsConfig={{
          render: mockActionsRender,
        }}
      />,
    );

    const firstRow = screen.getByText("Item 1").closest("tr");
    const secondRow = screen.getByText("Item 2").closest("tr");

    if (firstRow && secondRow) {
      fireEvent.mouseEnter(firstRow);

      await waitFor(() => {
        expect(screen.getByTestId("action-1")).toBeInTheDocument();
        expect(screen.queryByTestId("action-2")).not.toBeInTheDocument();
      });

      fireEvent.mouseLeave(firstRow);
      fireEvent.mouseEnter(secondRow);

      await waitFor(() => {
        expect(screen.queryByTestId("action-1")).not.toBeInTheDocument();
        expect(screen.getByTestId("action-2")).toBeInTheDocument();
      });
    }
  });

  it("should stop click propagation on actions overlay", async () => {
    const onRowClick = vi.fn();

    render(
      <DataTable
        columns={mockColumns}
        data={mockData}
        getRowId={(row) => row.id}
        onRowClick={onRowClick}
        actionsConfig={{
          render: mockActionsRender,
        }}
      />,
    );

    const firstRow = screen.getByText("Item 1").closest("tr");

    if (firstRow) {
      fireEvent.mouseEnter(firstRow);

      await waitFor(() => {
        expect(screen.getByTestId("action-1")).toBeInTheDocument();
      });

      const actionsButton = screen.getByTestId("action-1");
      fireEvent.click(actionsButton);

      expect(onRowClick).not.toHaveBeenCalled();
    }
  });

  it("should work without actionsConfig", () => {
    render(<DataTable columns={mockColumns} data={mockData} />);

    const firstRow = screen.getByText("Item 1").closest("tr");

    if (firstRow) {
      fireEvent.mouseEnter(firstRow);

      expect(mockActionsRender).not.toHaveBeenCalled();
    }
  });

  it("should handle null return from actionsConfig.render gracefully", async () => {
    const mockRenderWithNull = vi.fn((row: Row<TestData>) => {
      if (parseInt(row.original.id) % 2 === 0) {
        return null;
      }
      return <button data-testid={`action-${row.original.id}`}>Action</button>;
    });

    render(
      <DataTable
        columns={mockColumns}
        data={mockData}
        getRowId={(row) => row.id}
        actionsConfig={{
          render: mockRenderWithNull,
        }}
      />,
    );

    const firstRow = screen.getByText("Item 1").closest("tr");
    const secondRow = screen.getByText("Item 2").closest("tr");

    if (firstRow && secondRow) {
      fireEvent.mouseEnter(firstRow);

      await waitFor(() => {
        expect(screen.getByTestId("action-1")).toBeInTheDocument();
      });

      fireEvent.mouseLeave(firstRow);
      fireEvent.mouseEnter(secondRow);

      await waitFor(() => {
        expect(screen.queryByTestId("action-1")).not.toBeInTheDocument();
        expect(screen.queryByTestId("action-2")).not.toBeInTheDocument();
      });

      expect(mockRenderWithNull).toHaveBeenCalled();
    }
  });

  it("should render actions column header and cells even when actions return null", () => {
    const mockRenderAlwaysNull = vi.fn(() => null);

    const { container } = render(
      <DataTable
        columns={mockColumns}
        data={mockData}
        getRowId={(row) => row.id}
        actionsConfig={{
          render: mockRenderAlwaysNull,
        }}
      />,
    );

    const colElements = container.querySelectorAll("colgroup col");
    expect(colElements.length).toBe(3);

    const headerCells = container.querySelectorAll("thead th");
    expect(headerCells.length).toBe(3);
  });
});
