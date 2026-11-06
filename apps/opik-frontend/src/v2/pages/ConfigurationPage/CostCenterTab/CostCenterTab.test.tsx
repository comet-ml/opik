import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import { TooltipProvider } from "@/ui/tooltip";
import {
  ModelPricesAccess,
  ModelPricesAccessProvider,
} from "@/contexts/ModelPricesAccessContext";

const { LIST, HISTORY, BUILT_INS, mutateAsync, refetch } = vi.hoisted(() => {
  const gpt4o = {
    id: "row-1",
    model: "gpt-4o",
    provider: "openai",
    kind: "fixed" as const,
    input_price: "2",
    note: "Contract rate",
  };
  return {
    LIST: {
      version: 3,
      rows: [
        gpt4o,
        {
          id: "row-2",
          model: "acme-llama-70b",
          provider: "",
          kind: "fixed" as const,
          input_price: "0.6",
          output_price: "0.6",
        },
        {
          id: "row-3",
          model: "team-model",
          provider: "gateway",
          kind: "alias" as const,
          alias_of: { model: "claude-sonnet-4-5", provider: "anthropic" },
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
    BUILT_INS: [
      {
        model: "gpt-4o",
        provider: "openai",
        mode: "chat",
        input_price: "2.5",
        output_price: "10",
        cache_read_price: "1.25",
      },
      {
        model: "gpt-4o-mini",
        provider: "openai",
        mode: "chat",
        input_price: "0.15",
        output_price: "0.6",
      },
      {
        model: "claude-sonnet-4-5",
        provider: "anthropic",
        mode: "chat",
        input_price: "3",
        output_price: "15",
      },
    ],
    mutateAsync: vi.fn(),
    refetch: vi.fn(),
  };
});

const current = vi.hoisted(() => ({
  modelsUsed: undefined as unknown,
}));

vi.mock("@/api/model-prices/useModelPrices", () => ({
  default: () => ({ data: LIST, isPending: false, isError: false, refetch }),
}));

vi.mock("@/api/model-prices/useModelPriceHistory", () => ({
  default: () => ({ data: HISTORY }),
}));

vi.mock("@/api/model-prices/useModelsUsed", () => ({
  default: () => ({ data: current.modelsUsed }),
}));

vi.mock("@/api/model-prices/useModelPricesReplaceMutation", () => ({
  default: () => ({ mutateAsync, isPending: false }),
}));

vi.mock("./useBuiltInPrices", () => ({
  default: () => ({ data: BUILT_INS, isPending: false }),
}));

vi.mock("@/store/AppStore", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/store/AppStore")>()),
  useActiveWorkspaceName: () => "research",
}));

import CostCenterTab from "./CostCenterTab";

const renderTab = (access?: ModelPricesAccess) =>
  render(
    <QueryParamProvider adapter={WindowHistoryAdapter}>
      <TooltipProvider>
        {access ? (
          <ModelPricesAccessProvider value={access}>
            <CostCenterTab />
          </ModelPricesAccessProvider>
        ) : (
          <CostCenterTab />
        )}
      </TooltipProvider>
    </QueryParamProvider>,
  );

const withFilters = (filters: object[]) =>
  window.history.replaceState(
    {},
    "",
    `/?prices_filters=${encodeURIComponent(JSON.stringify(filters))}`,
  );

const quickAccessModels = () =>
  within(screen.getByTestId("quick-access"))
    .getAllByRole("button", { name: /^Open / })
    .map((card) => card.textContent);

const ADMIN: ModelPricesAccess = {
  canEdit: true,
  isPending: false,
  organizationName: "Acme",
};

const shownModels = () =>
  screen
    .queryAllByTestId("model-price-row")
    .map((row) => row.querySelector(".comet-code, .font-mono")?.textContent);

const rowOf = (model: string) =>
  screen
    .getAllByTestId("model-price-row")
    .find((row) => within(row).queryByText(model, { exact: true }))!;

const openRow = (model: string) => fireEvent.click(rowOf(model));

const priceInput = (label: string) =>
  screen.getByRole("textbox", { name: `${label} price per 1M tokens` });

const replaceResult = (dryRun: boolean, warnings: unknown[] = []) => ({
  version: dryRun ? 3 : 4,
  saved: !dryRun,
  created: [],
  updated: [],
  deleted: [],
  unchanged: 2,
  warnings,
});

const sentRows = () => mutateAsync.mock.lastCall![0].request.rows;

describe("CostCenterTab", () => {
  beforeEach(() => {
    mutateAsync.mockReset();
    refetch.mockReset();
    current.modelsUsed = undefined;
    window.history.replaceState({}, "", "/");
    window.localStorage.clear();
  });

  describe("access", () => {
    it("shows the catalog read-only to an organization member", () => {
      renderTab({ canEdit: false, isPending: false, organizationName: "Acme" });

      expect(
        screen.getByText("View only · ask an organization admin"),
      ).toBeVisible();
      expect(screen.queryByRole("button", { name: /Add model/ })).toBeNull();
      expect(screen.queryByRole("button", { name: /Batch update/ })).toBeNull();

      openRow("gpt-4o");

      expect(priceInput("Input")).toBeDisabled();
      expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
      expect(screen.queryByRole("button", { name: /Add price/ })).toBeNull();
    });

    it("hides the controls a member can't use on a custom model", () => {
      renderTab({ canEdit: false, isPending: false, organizationName: "Acme" });

      openRow("acme-llama-70b");

      expect(priceInput("Input")).toBeDisabled();
      expect(screen.queryByRole("button", { name: "Own price" })).toBeNull();
      expect(screen.queryByText(/Cached tokens are billed/)).toBeNull();
    });

    it("stays read-only while the role is loading, without the view-only badge", () => {
      renderTab({ canEdit: true, isPending: true, organizationName: "Acme" });

      expect(screen.queryByRole("button", { name: /Add model/ })).toBeNull();
      expect(
        screen.queryByText("View only · ask an organization admin"),
      ).toBeNull();
    });

    it("lets everyone edit on open source, where there is no organization", () => {
      renderTab();

      expect(
        screen.getByText(
          "Model prices used to estimate cost. Changes apply to spans logged after you save.",
        ),
      ).toBeVisible();
      expect(screen.queryByText(/One list for every workspace/)).toBeNull();
      expect(screen.getByRole("button", { name: /Add model/ })).toBeVisible();
      expect(
        screen.getByRole("button", { name: /Batch update/ }),
      ).toBeVisible();
    });

    it("doesn't claim who may edit while the access is still loading", () => {
      renderTab({ canEdit: true, isPending: true });

      expect(
        screen.getByText(/Checking who can edit these prices\./),
      ).toBeVisible();
    });
  });

  it("shows the version and who saved it last", () => {
    renderTab(ADMIN);

    expect(
      screen.getByText(/Organization prices: version 3 · saved by maria/),
    ).toBeVisible();
  });

  describe("catalog", () => {
    it("merges built-in prices with the organization's rows", () => {
      renderTab(ADMIN);

      expect(shownModels()).toEqual([
        "acme-llama-70b",
        "claude-sonnet-4-5",
        "gpt-4o",
        "gpt-4o-mini",
        "team-model",
      ]);
      expect(within(rowOf("gpt-4o")).getByText("Override")).toBeVisible();
      expect(within(rowOf("gpt-4o")).getByText("$2.00")).toBeVisible();
      expect(within(rowOf("gpt-4o")).getByText("$10.00")).toBeVisible();
      expect(
        within(rowOf("acme-llama-70b")).getAllByText("$0.600"),
      ).toHaveLength(2);
      expect(within(rowOf("gpt-4o")).getByText("+1 field")).toBeVisible();
      expect(within(rowOf("gpt-4o-mini")).getByText("Default")).toBeVisible();
      expect(within(rowOf("acme-llama-70b")).getByText("Custom")).toBeVisible();
      expect(
        within(rowOf("acme-llama-70b")).getByText("Any provider"),
      ).toBeVisible();
      expect(rowOf("team-model")).toHaveTextContent(
        "Priced like claude-sonnet-4-5",
      );
    });

    it("sorts by model in both directions", () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Sort by model/ }));

      expect(shownModels()[0]).toBe("team-model");
    });

    it("goes back to the first page when the sort changes", () => {
      BUILT_INS.push(
        ...Array.from({ length: 30 }, (_, i) => ({
          model: `zz-model-${String(i).padStart(2, "0")}`,
          provider: "openai",
          mode: "chat",
          input_price: "1",
          output_price: "2",
        })),
      );
      try {
        renderTab(ADMIN);
        fireEvent.click(
          document.querySelector(".lucide-chevron-right")!.closest("button")!,
        );
        expect(screen.getByText(/^Showing 26-/)).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: /Sort by model/ }));

        expect(screen.getByText(/^Showing 1-25/)).toBeInTheDocument();
      } finally {
        BUILT_INS.splice(3);
      }
    });

    it.each([
      ["the model", "LLAMA", ["acme-llama-70b"]],
      ["the provider", "gateway", ["team-model"]],
      [
        "the model an alias points to",
        "sonnet",
        ["claude-sonnet-4-5", "team-model"],
      ],
    ])("finds rows by %s", async (_, needle, expected) => {
      renderTab(ADMIN);

      fireEvent.change(
        screen.getByPlaceholderText("Search models or providers"),
        { target: { value: needle } },
      );

      await waitFor(() => expect(shownModels()).toEqual(expected));
    });

    it("says when nothing matches", async () => {
      renderTab(ADMIN);

      fireEvent.change(
        screen.getByPlaceholderText("Search models or providers"),
        { target: { value: "zzz" } },
      );

      expect(
        await screen.findByText("No prices match these filters."),
      ).toBeVisible();
    });

    it("filters by type from the filter chips", () => {
      withFilters([
        {
          id: "f1",
          field: "type",
          type: "string",
          operator: "=",
          value: "Custom",
        },
      ]);
      renderTab(ADMIN);

      expect(shownModels()).toEqual(["acme-llama-70b", "team-model"]);
      expect(screen.getByTestId("filter-chip-type")).toHaveTextContent(
        "Custom",
      );
    });

    it("finds models with no provider through the Any provider option", () => {
      withFilters([
        {
          id: "f1",
          field: "provider",
          type: "string",
          operator: "=",
          value: "Any provider",
        },
      ]);
      renderTab(ADMIN);

      expect(shownModels()).toEqual(["acme-llama-70b"]);
    });

    it("puts exact matches first when searching", async () => {
      renderTab(ADMIN);

      fireEvent.change(
        screen.getByPlaceholderText("Search models or providers"),
        { target: { value: "gpt-4o" } },
      );

      await waitFor(() =>
        expect(shownModels()).toEqual(["gpt-4o", "gpt-4o-mini"]),
      );
    });
  });

  describe("quick access", () => {
    const usedModel = (
      model: string,
      provider: string,
      daysUsed: number,
      lastSeen: string,
    ) => ({
      model,
      provider,
      last_seen: lastSeen,
      days_used: daysUsed,
      workspaces: [{ id: "research", name: "research" }],
      price_source: "built_in" as const,
    });

    it("shows the most used models first, newest first on a tie", () => {
      current.modelsUsed = {
        content: [
          usedModel("gpt-4o-mini", "openai", 2, "2026-11-05T10:00:00Z"),
          usedModel(
            "claude-sonnet-4-5",
            "anthropic",
            9,
            "2026-11-01T10:00:00Z",
          ),
          usedModel("gpt-4o", "openai", 2, "2026-11-06T10:00:00Z"),
        ],
      };
      renderTab(ADMIN);

      expect(quickAccessModels()).toEqual([
        "claude-sonnet-4-5",
        "gpt-4o",
        "gpt-4o-mini",
      ]);
    });

    it("puts a model pinned from the table first and remembers it", () => {
      const { unmount } = renderTab(ADMIN);

      fireEvent.click(
        screen.getByRole("button", { name: "Pin gpt-4o-mini to quick access" }),
      );

      expect(quickAccessModels()[0]).toBe("gpt-4o-mini");
      expect(screen.queryByLabelText("Model name")).toBeNull();

      unmount();
      renderTab(ADMIN);

      expect(quickAccessModels()[0]).toBe("gpt-4o-mini");
      fireEvent.click(
        within(screen.getByTestId("quick-access")).getByRole("button", {
          name: "Unpin gpt-4o-mini from quick access",
        }),
      );
      expect(quickAccessModels()).not.toContain("gpt-4o-mini");
    });

    it("stops pinning once quick access holds 8 models", () => {
      const extra = ["m-1", "m-2", "m-3", "m-4"].map((model) => ({
        model,
        provider: "acme",
        mode: "chat",
        input_price: "1",
        output_price: "2",
      }));
      BUILT_INS.push(...extra);
      try {
        renderTab(ADMIN);
        const pinButton = (model: string) =>
          within(rowOf(model)).getByRole("button", {
            name: `Pin ${model} to quick access`,
          });
        const models = shownModels() as string[];
        models
          .slice(0, 8)
          .forEach((model) => fireEvent.click(pinButton(model)));

        expect(quickAccessModels()).toHaveLength(8);
        const ninth = pinButton(models[8]);
        expect(ninth).toHaveAttribute("aria-disabled", "true");
        fireEvent.click(ninth);
        expect(quickAccessModels()).not.toContain(models[8]);
        expect(screen.queryByLabelText("Model name")).toBeNull();
      } finally {
        BUILT_INS.splice(3);
      }
    });
  });

  describe("editing one model", () => {
    it("opens a model from the keyboard and keeps it open", () => {
      renderTab(ADMIN);

      const row = rowOf("gpt-4o");
      row.focus();
      const notPrevented = fireEvent.keyDown(row, { key: "Enter" });
      fireEvent.keyUp(row, { key: "Enter" });

      expect(notPrevented).toBe(false);
      expect(priceInput("Input")).toBeInTheDocument();
    });

    it("gives focus back to the row once the drawer closes", async () => {
      renderTab(ADMIN);

      const row = rowOf("gpt-4o");
      row.focus();
      fireEvent.keyDown(row, { key: "Enter" });
      expect(row).not.toHaveFocus();
      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      await waitFor(() => expect(row).toHaveFocus());
    });

    it("asks before throwing away unsaved edits", () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.change(priceInput("Input"), { target: { value: "1.9" } });
      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
      fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));

      expect(priceInput("Input")).toHaveValue("1.9");

      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
      fireEvent.click(screen.getByRole("button", { name: "Discard" }));

      expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
      expect(mutateAsync).not.toHaveBeenCalled();
    });

    it("closes at once when nothing was edited", () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      expect(screen.queryByText("Discard your changes?")).toBeNull();
      expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
    });

    it("asks before removing a custom model", async () => {
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun),
      );
      renderTab(ADMIN);

      openRow("acme-llama-70b");
      fireEvent.click(screen.getByRole("button", { name: /Remove model/ }));

      expect(mutateAsync).not.toHaveBeenCalled();
      fireEvent.click(
        within(
          screen.getByRole("dialog", { name: "Remove acme-llama-70b?" }),
        ).getByRole("button", { name: "Remove model" }),
      );

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(sentRows().map((row: { model: string }) => row.model)).toEqual([
        "gpt-4o",
        "team-model",
      ]);
    });

    it("previews, then saves only the fields that differ from the built-in price", async () => {
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun),
      );
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.change(priceInput("Input"), { target: { value: "1.9" } });
      fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(mutateAsync.mock.calls[0][0].dryRun).toBe(true);
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
              note: "Contract rate",
            },
            {
              model: "acme-llama-70b",
              provider: "",
              kind: "fixed",
              input_price: "0.6",
              output_price: "0.6",
            },
            {
              model: "team-model",
              provider: "gateway",
              kind: "alias",
              alias_of: { model: "claude-sonnet-4-5", provider: "anthropic" },
            },
          ],
        },
      });
      await waitFor(() =>
        expect(
          screen.queryByRole("button", { name: "Save changes" }),
        ).toBeNull(),
      );
    });

    it("removes the organization row when all defaults are restored", async () => {
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun),
      );
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.click(
        screen.getByRole("button", { name: /Restore all defaults/ }),
      );

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(sentRows().map((row: { model: string }) => row.model)).toEqual([
        "acme-llama-70b",
        "team-model",
      ]);
    });

    it("closes without saving when a built-in model is left unchanged", () => {
      renderTab(ADMIN);

      openRow("gpt-4o-mini");
      fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

      expect(mutateAsync).not.toHaveBeenCalled();
      expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
    });

    it("asks before saving a model with warnings, then saves without another preview", async () => {
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun, [
          {
            row: 0,
            field: "input_price",
            code: "big_change",
            message: "Input changes by more than 50%",
          },
          {
            row: 2,
            field: "provider",
            code: "no_built_in",
            message: "No built-in price is filed under 'gateway'",
          },
        ]),
      );
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.change(priceInput("Input"), { target: { value: "0.5" } });
      fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

      const warnings = await screen.findByTestId("model-price-warnings");
      expect(warnings).toHaveTextContent("Input changes by more than 50%");
      expect(warnings).not.toHaveTextContent("gateway");
      expect(mutateAsync).toHaveBeenCalledTimes(1);

      fireEvent.click(screen.getByRole("button", { name: "Save anyway" }));

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(mutateAsync.mock.lastCall![0].dryRun).toBe(false);
    });

    it("shows the backend's 422 problems for this model under its field", async () => {
      mutateAsync.mockRejectedValue({
        response: {
          status: 422,
          data: {
            errors: [
              {
                row: 0,
                field: "input_price",
                code: "price_too_high",
                message: "input_price is over 100000 per 1M tokens",
              },
              {
                row: 1,
                field: "output_price",
                code: "price_too_high",
                message: "someone else's row",
              },
            ],
            warnings: [],
          },
        },
      });
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.change(priceInput("Input"), { target: { value: "1.9" } });
      fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

      expect(
        await screen.findByText("input_price is over 100000 per 1M tokens"),
      ).toBeVisible();
      expect(screen.queryByText("someone else's row")).toBeNull();
    });

    it("reloads the list and keeps the edit when someone else saved first", async () => {
      mutateAsync.mockRejectedValue({ response: { status: 409 } });
      refetch.mockResolvedValue({ isSuccess: true, data: LIST });
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.change(priceInput("Input"), { target: { value: "1.9" } });
      fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

      await waitFor(() => expect(refetch).toHaveBeenCalled());
      expect(priceInput("Input")).toHaveValue("1.9");
      expect(
        screen.getByRole("button", { name: "Save changes" }),
      ).toBeVisible();
    });

    it("won't add a second row for a model the organization already prices", () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      fireEvent.change(screen.getByLabelText("Model name"), {
        target: { value: "ACME-llama-70b" },
      });
      fireEvent.change(priceInput("Input"), { target: { value: "1" } });
      fireEvent.click(screen.getByRole("button", { name: "Add model" }));

      expect(
        screen.getByText(/This model already has an organization price/),
      ).toBeVisible();
      expect(mutateAsync).not.toHaveBeenCalled();
    });
  });

  describe("models without a price", () => {
    const used = (
      model: string,
      workspace: string,
      priceSource: "none" | "built_in",
    ) => ({
      model,
      provider: "",
      last_seen: "2026-11-05T10:00:00Z",
      days_used: 3,
      workspaces: [{ id: workspace, name: workspace }],
      price_source: priceSource,
    });

    it("lists the current workspace's unpriced models and prefills a new price", async () => {
      current.modelsUsed = {
        content: [
          used("acme-finetune-v3", "research", "none"),
          used("other-model", "sales", "none"),
          used("gpt-4o-mini", "research", "built_in"),
        ],
      };
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun),
      );
      renderTab(ADMIN);

      const banner = screen.getByTestId("no-price-banner");
      expect(banner).toHaveTextContent(
        "1 model in your workspace has no price",
      );
      fireEvent.click(within(banner).getByRole("button", { expanded: false }));
      expect(within(banner).queryByText("other-model")).toBeNull();
      fireEvent.click(
        within(banner).getByRole("button", { name: "Add prices" }),
      );

      expect(screen.getByLabelText("Model name")).toHaveValue(
        "acme-finetune-v3",
      );
      fireEvent.change(priceInput("Input"), { target: { value: "2" } });
      fireEvent.change(priceInput("Output"), { target: { value: "8" } });
      fireEvent.click(screen.getByRole("button", { name: "Add model" }));

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(sentRows()[3]).toEqual({
        model: "acme-finetune-v3",
        provider: "",
        kind: "fixed",
        input_price: "2",
        output_price: "8",
      });
    });

    it("saves a new custom price without asking about its provider", async () => {
      current.modelsUsed = {
        content: [used("acme-finetune-v3", "research", "none")],
      };
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun, [
          {
            row: 3,
            field: "provider",
            code: "provider_unpriced",
            message: "No built-in prices for this provider",
          },
          {
            row: 3,
            field: null,
            code: "no_base",
            message: "No built-in price to compare with",
          },
        ]),
      );
      renderTab(ADMIN);

      const banner = screen.getByTestId("no-price-banner");
      fireEvent.click(within(banner).getByRole("button", { expanded: false }));
      fireEvent.click(
        within(banner).getByRole("button", { name: "Add prices" }),
      );
      fireEvent.change(priceInput("Input"), { target: { value: "2" } });
      fireEvent.change(priceInput("Output"), { target: { value: "8" } });
      fireEvent.click(screen.getByRole("button", { name: "Add model" }));

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(mutateAsync.mock.lastCall![0].dryRun).toBe(false);
      expect(screen.queryByTestId("model-price-warnings")).toBeNull();
    });

    it("falls back to the whole organization when this workspace has none", () => {
      current.modelsUsed = {
        content: [used("other-model", "sales", "none")],
      };
      renderTab(ADMIN);

      expect(screen.getByTestId("no-price-banner")).toHaveTextContent(
        "1 model in your organization has no price",
      );
    });

    it("stays hidden when every model has a price", () => {
      current.modelsUsed = {
        content: [used("gpt-4o-mini", "research", "built_in")],
      };
      renderTab(ADMIN);

      expect(screen.queryByTestId("no-price-banner")).toBeNull();
    });
  });

  describe("batch update", () => {
    const UPLOAD = JSON.stringify({
      "gpt-4o": { litellm_provider: "openai", input_cost_per_token: 0.0000018 },
      "fresh-model": {
        input_cost_per_token: 0.000001,
        output_cost_per_token: 0.000002,
      },
    });

    it("previews the upload merged into the list, then saves it", async () => {
      mutateAsync.mockImplementation(async ({ dryRun, request }) => ({
        ...replaceResult(dryRun),
        created: [request.rows[3]],
        updated: [{ before: LIST.rows[0], after: request.rows[0] }],
      }));
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Batch update/ }));
      fireEvent.change(screen.getByRole("textbox"), {
        target: { value: UPLOAD },
      });
      fireEvent.click(screen.getByRole("button", { name: "Preview changes" }));

      expect(await screen.findByTestId("batch-preview")).toHaveTextContent(
        "1 added, 1 changed",
      );
      expect(mutateAsync.mock.lastCall![0].dryRun).toBe(true);
      expect(sentRows().map((row: { model: string }) => row.model)).toEqual([
        "gpt-4o",
        "acme-llama-70b",
        "team-model",
        "fresh-model",
      ]);
      expect(sentRows()[0]).toMatchObject({
        input_price: "1.8",
        note: "Contract rate",
      });

      fireEvent.click(screen.getByRole("button", { name: "Save 2 changes" }));

      await waitFor(() =>
        expect(mutateAsync.mock.lastCall![0].dryRun).toBe(false),
      );
    });

    it("counts the uploaded models that stay the same", async () => {
      mutateAsync.mockImplementation(async ({ dryRun, request }) => ({
        ...replaceResult(dryRun),
        created: [request.rows[3]],
        updated: [{ before: LIST.rows[0], after: request.rows[0] }],
      }));
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Batch update/ }));
      fireEvent.change(screen.getByRole("textbox"), {
        target: {
          value: JSON.stringify({
            ...JSON.parse(UPLOAD),
            "acme-llama-70b": {
              input_cost_per_token: 0.0000006,
              output_cost_per_token: 0.0000006,
            },
          }),
        },
      });
      fireEvent.click(screen.getByRole("button", { name: "Preview changes" }));

      expect(await screen.findByTestId("batch-preview")).toHaveTextContent(
        "1 added, 1 changed, 1 unchanged",
      );
    });

    it("names the model and the field when the backend rejects a price", async () => {
      mutateAsync.mockRejectedValue({
        response: {
          status: 422,
          data: {
            errors: [
              {
                row: 0,
                field: "input_price",
                code: "price_too_high",
                message: "input_price is over 100000 per 1M tokens",
              },
            ],
            warnings: [],
          },
        },
      });
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Batch update/ }));
      fireEvent.change(screen.getByRole("textbox"), {
        target: { value: UPLOAD },
      });
      fireEvent.click(screen.getByRole("button", { name: "Preview changes" }));

      expect(
        await screen.findByText(
          "gpt-4o on openai: Input price is over $100,000 per 1M tokens. Prices in the file are per token.",
        ),
      ).toBeVisible();
    });

    it("asks before throwing away a pasted update", () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Batch update/ }));
      fireEvent.change(screen.getByRole("textbox"), {
        target: { value: UPLOAD },
      });
      fireEvent.click(screen.getByRole("button", { name: "Close" }));
      fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));

      expect(screen.getByRole("textbox")).toHaveValue(UPLOAD);

      fireEvent.click(screen.getByRole("button", { name: "Close" }));
      fireEvent.click(screen.getByRole("button", { name: "Discard" }));

      expect(screen.queryByText("Batch update prices")).toBeNull();
    });

    it("shows only the warnings about the uploaded models", async () => {
      mutateAsync.mockImplementation(async ({ dryRun, request }) => ({
        ...replaceResult(dryRun, [
          { row: 0, field: "input_price", message: "gpt-4o got cheaper" },
          { row: 1, field: "model", message: "someone else's row" },
          { row: 3, field: "output_price", message: "fresh-model is pricey" },
        ]),
        created: [request.rows[3]],
      }));
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Batch update/ }));
      fireEvent.change(screen.getByRole("textbox"), {
        target: { value: UPLOAD },
      });
      fireEvent.click(screen.getByRole("button", { name: "Preview changes" }));

      const warnings = await screen.findByTestId("batch-preview-warnings");
      expect(warnings).toHaveTextContent("Check these before saving");
      expect(within(warnings).getAllByRole("listitem")).toHaveLength(2);
      expect(warnings).not.toHaveTextContent("someone else's row");
    });

    it("explains what is wrong with the file instead of sending it", () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Batch update/ }));
      fireEvent.change(screen.getByRole("textbox"), {
        target: { value: "{ not json" },
      });
      fireEvent.click(screen.getByRole("button", { name: "Preview changes" }));

      expect(screen.getByText(/This isn't valid JSON/)).toBeVisible();
      expect(mutateAsync).not.toHaveBeenCalled();
    });
  });
});
