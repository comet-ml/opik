import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import {
  ModelPricesAccess,
  ModelPricesAccessProvider,
} from "@/contexts/ModelPricesAccessContext";

const { LIST, HISTORY, mutateAsync, refetch } = vi.hoisted(() => {
  const gpt4o = {
    id: "row-1",
    model: "gpt-4o",
    provider: "openai",
    kind: "fixed" as const,
    input_price: "2",
    output_price: "8",
    note: "Contract rate",
  };
  return {
    LIST: {
      version: 3,
      rows: [
        gpt4o,
        {
          id: "row-2",
          model: "bifrost/gpt-4o",
          provider: "",
          kind: "alias" as const,
          alias_of: { model: "gpt-4o", provider: "openai" },
        },
      ],
    },
    HISTORY: {
      page: 1,
      size: 100,
      total: 1,
      content: [
        {
          list_version: 3,
          row_id: "row-1",
          action: "created" as const,
          after: gpt4o,
          created_at: "2026-11-01T10:00:00Z",
          created_by: "maria",
        },
      ],
    },
    mutateAsync: vi.fn(),
    refetch: vi.fn(),
  };
});

vi.mock("@/api/model-prices/useModelPrices", () => ({
  default: () => ({ data: LIST, isPending: false, isError: false, refetch }),
}));

vi.mock("@/api/model-prices/useModelPriceHistory", () => ({
  default: () => ({ data: HISTORY }),
}));

vi.mock("@/api/model-prices/useModelPricesReplaceMutation", () => ({
  default: () => ({ mutateAsync, isPending: false }),
}));

import CostCenterTab from "./CostCenterTab";

const renderTab = (access?: ModelPricesAccess) =>
  render(
    <TooltipProvider>
      {access ? (
        <ModelPricesAccessProvider value={access}>
          <CostCenterTab />
        </ModelPricesAccessProvider>
      ) : (
        <CostCenterTab />
      )}
    </TooltipProvider>,
  );

const ADMIN: ModelPricesAccess = {
  canEdit: true,
  isPending: false,
  organizationName: "Acme",
};

const inputPrice = () =>
  screen.getByRole("textbox", { name: "Input price for gpt-4o" });

describe("CostCenterTab", () => {
  beforeEach(() => {
    mutateAsync.mockReset();
    refetch.mockReset();
  });

  it("shows the list read-only to an organization member", () => {
    renderTab({ canEdit: false, isPending: false, organizationName: "Acme" });

    expect(screen.getByText("Acme · organization price list")).toBeVisible();
    expect(
      screen.getByText("View only · ask an organization admin"),
    ).toBeVisible();
    expect(screen.queryByRole("button", { name: /Add price/ })).toBeNull();
    expect(screen.queryByRole("textbox", { name: /price for/ })).toBeNull();
    expect(screen.getByText("gpt-4o on openai")).toBeVisible();
  });

  it("stays read-only while the role is loading, without the view-only badge", () => {
    renderTab({ canEdit: true, isPending: true, organizationName: "Acme" });

    expect(screen.queryByRole("button", { name: /Add price/ })).toBeNull();
    expect(screen.queryByRole("textbox", { name: /price for/ })).toBeNull();
    expect(
      screen.queryByText("View only · ask an organization admin"),
    ).toBeNull();
  });

  it("lets everyone edit on open source, where there is no organization", () => {
    renderTab();

    expect(screen.getByText("Organization price list")).toBeVisible();
    expect(
      screen.getByText(/One list for every workspace on this installation/),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: /Add price/ })).toBeVisible();
  });

  it("shows the version and who saved it last", () => {
    renderTab(ADMIN);

    expect(screen.getByText(/Version 3 · saved by maria/)).toBeVisible();
  });

  it("counts an edit in the save bar and marks the row", () => {
    renderTab(ADMIN);

    fireEvent.change(inputPrice(), { target: { value: "1.9" } });

    expect(screen.getByTestId("model-prices-save-bar")).toHaveTextContent(
      "Unsaved changes: 1 edited.",
    );
    expect(screen.getByText("Edited")).toBeVisible();
  });

  it("blocks saving while a row is invalid", () => {
    renderTab(ADMIN);

    fireEvent.change(inputPrice(), { target: { value: "abc" } });

    expect(
      screen.getByText("Input: Type a plain number, like 2.50"),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "Review and save" })).toBeDisabled();
  });

  it("forgets every change on discard", () => {
    renderTab(ADMIN);

    fireEvent.change(inputPrice(), { target: { value: "1.9" } });
    fireEvent.click(screen.getByRole("button", { name: "Discard" }));

    expect(inputPrice()).toHaveValue("2");
    expect(screen.queryByTestId("model-prices-save-bar")).toBeNull();
  });

  it("previews the change, then saves the whole list at the version it was read", async () => {
    mutateAsync.mockImplementation(
      async ({ dryRun }: { dryRun: boolean }) => ({
        version: dryRun ? 3 : 4,
        saved: !dryRun,
        created: [],
        updated: [
          {
            before: LIST.rows[0],
            after: { ...LIST.rows[0], input_price: "1.9" },
          },
        ],
        deleted: [],
        unchanged: 1,
        warnings: [],
      }),
    );
    refetch.mockResolvedValue({ data: LIST });
    renderTab(ADMIN);

    fireEvent.change(inputPrice(), { target: { value: "1.9" } });
    fireEvent.click(screen.getByRole("button", { name: "Review and save" }));

    expect(
      await screen.findByText("gpt-4o on openai: input 2 → 1.9"),
    ).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Save prices" }));

    await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
    expect(mutateAsync).toHaveBeenLastCalledWith({
      dryRun: false,
      request: {
        expected_version: 3,
        rows: [
          {
            model: "gpt-4o",
            provider: "openai",
            kind: "fixed",
            input_price: "1.9",
            output_price: "8",
            note: "Contract rate",
          },
          {
            model: "bifrost/gpt-4o",
            provider: "",
            kind: "alias",
            alias_of: { model: "gpt-4o", provider: "openai" },
          },
        ],
      },
    });
    await waitFor(() =>
      expect(screen.queryByTestId("model-prices-save-bar")).toBeNull(),
    );
  });

  it("puts the backend's 422 problems on the row they belong to", async () => {
    mutateAsync.mockRejectedValue({
      response: {
        status: 422,
        data: {
          errors: [
            {
              row: 0,
              field: "output_price",
              code: "price_too_high",
              message: "output_price is over 100000 per 1M tokens",
            },
          ],
          warnings: [],
        },
      },
    });
    renderTab(ADMIN);

    fireEvent.change(inputPrice(), { target: { value: "1.9" } });
    fireEvent.click(screen.getByRole("button", { name: "Review and save" }));

    const problem = await screen.findByText(
      "Output: output_price is over 100000 per 1M tokens",
    );
    const rowAbove = problem.closest("tr")?.previousElementSibling;
    expect(rowAbove).toHaveTextContent("gpt-4o");
    expect(rowAbove).not.toHaveTextContent("bifrost");
  });
});
