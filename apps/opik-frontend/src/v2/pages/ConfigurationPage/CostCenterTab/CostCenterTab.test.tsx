import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  act,
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

const { LIST, HISTORY, BUILT_INS, mutateAsync, refetch, listState } =
  vi.hoisted(() => {
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
      listState: { current: undefined as unknown },
    };
  });

vi.mock("@/api/model-prices/useModelPrices", () => ({
  default: () => ({
    data: listState.current ?? LIST,
    isPending: false,
    isError: false,
    refetch,
  }),
}));

vi.mock("@/api/model-prices/useModelPriceHistory", () => ({
  default: () => ({ data: HISTORY }),
}));

vi.mock("@/api/model-prices/useModelPricesReplaceMutation", () => ({
  default: () => ({ mutateAsync, isPending: false }),
}));

vi.mock("./useBuiltInPrices", () => ({
  default: () => ({ data: BUILT_INS, isPending: false }),
}));

// DataTable waits 200 ms before a row click counts, so text can be selected.
vi.mock("@/shared/DataTable/useCustomRowClick", () => ({
  default: () => (_event: unknown, onClick: () => void) => onClick(),
}));

import CostCenterTab from "./CostCenterTab";

const tabTree = (access?: ModelPricesAccess) => (
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
  </QueryParamProvider>
);

const renderTab = (access?: ModelPricesAccess) => render(tabTree(access));

const withFilters = (filters: object[]) =>
  window.history.replaceState(
    {},
    "",
    `/?prices_filters=${encodeURIComponent(JSON.stringify(filters))}`,
  );

const ADMIN: ModelPricesAccess = {
  canEdit: true,
  isPending: false,
  organizationName: "Acme",
};

const tableRows = () =>
  Array.from(document.querySelectorAll<HTMLElement>("tbody tr[data-row-id]"));

const shownModels = () =>
  tableRows().map((row) => row.querySelectorAll("td")[1]?.textContent);

const rowOf = (model: string) =>
  tableRows().find((row) => within(row).queryByText(model, { exact: true }))!;

const sortByModel = () =>
  fireEvent.click(
    within(screen.getByRole("columnheader", { name: /Model/ })).getByText(
      "Model",
    ),
  );

const pinButton = (model: string) =>
  within(rowOf(model)).getByRole("button", {
    name: /^(Pin to the top|Unpin)$/,
  });

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

// The picker hands typed text on after a short pause, or at once when the
// field loses focus, as it does when someone clicks Save.
const typeModelName = (value: string) => {
  const field = screen.getByLabelText("Model name");
  fireEvent.focus(field);
  fireEvent.change(field, { target: { value } });
  fireEvent.blur(field);
};

const clickOutside = async () => {
  // Radix starts listening for outside clicks a tick after a sheet opens, and
  // a modal acts on an outside press only once its click arrives.
  await act(() => new Promise((resolve) => setTimeout(resolve, 0)));
  fireEvent.pointerDown(document.body);
  fireEvent.click(document.body);
};

describe("CostCenterTab", () => {
  beforeEach(() => {
    mutateAsync.mockReset();
    refetch.mockReset();
    window.history.replaceState({}, "", "/");
    window.localStorage.clear();
    window.localStorage.setItem("cost-center:type-default-applied", "true");
    listState.current = undefined;
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

      expect(priceInput("Input")).toHaveAttribute("readonly");
      expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
      expect(screen.queryByRole("button", { name: /Add price/ })).toBeNull();
    });

    it("hides the controls a member can't use on a custom model", () => {
      renderTab({ canEdit: false, isPending: false, organizationName: "Acme" });

      openRow("acme-llama-70b");

      expect(priceInput("Input")).toHaveAttribute("readonly");
      expect(screen.queryByRole("radio", { name: "Own price" })).toBeNull();
      expect(screen.queryByText(/billed at the input price/)).toBeNull();
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

  it("opens the price history in a side sheet from the History button", async () => {
    renderTab(ADMIN);

    expect(screen.queryByTestId("model-price-history")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "History" }));

    const sheet = await screen.findByTestId("model-price-history");
    expect(within(sheet).getByText("v3")).toBeVisible();
    expect(within(sheet).getByText("maria")).toBeVisible();

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    await waitFor(() =>
      expect(screen.queryByTestId("model-price-history")).toBeNull(),
    );
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
      expect(within(rowOf("gpt-4o-mini")).getByText("Default")).toBeVisible();
      expect(within(rowOf("acme-llama-70b")).getByText("Custom")).toBeVisible();
      expect(
        within(rowOf("acme-llama-70b")).getByText("Any provider"),
      ).toBeVisible();
      expect(within(rowOf("team-model")).getByText("$3.00")).toBeVisible();
      expect(within(rowOf("team-model")).getByText("$15.00")).toBeVisible();
      expect(within(rowOf("team-model")).getByText("Alias")).toBeVisible();
    });

    it("shows the columns saved in this browser", () => {
      window.localStorage.setItem(
        "cost-center:selected-columns",
        JSON.stringify(["provider", "cache_read_price"]),
      );
      renderTab(ADMIN);

      expect(
        screen.getByRole("columnheader", { name: /Cache read/ }),
      ).toBeVisible();
      expect(within(rowOf("gpt-4o")).getByText("$1.25")).toBeVisible();
      expect(screen.queryByRole("columnheader", { name: /Input/ })).toBeNull();
    });

    it("adds a cache column from the Columns menu", async () => {
      renderTab(ADMIN);

      expect(
        screen.queryByRole("columnheader", { name: /Cache read/ }),
      ).toBeNull();

      fireEvent.pointerDown(
        screen.getByTestId("columns-button"),
        new PointerEvent("pointerdown", { bubbles: true, button: 0 }),
      );
      fireEvent.click(
        await screen.findByRole("button", { name: /^Cache read/ }),
      );
      fireEvent.keyDown(document.activeElement!, { key: "Escape" });

      expect(
        await screen.findByRole("columnheader", { name: /Cache read/ }),
      ).toBeVisible();
    });

    it("sorts by model in both directions", () => {
      renderTab(ADMIN);

      sortByModel();

      expect(shownModels()[0]).toBe("team-model");
    });

    it("remembers the sort in this browser", () => {
      const { unmount } = renderTab(ADMIN);

      sortByModel();
      expect(shownModels()[0]).toBe("team-model");

      unmount();
      window.history.replaceState({}, "", "/");
      renderTab(ADMIN);

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

        sortByModel();

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
        {
          target: { value: needle },
        },
      );

      await waitFor(() => expect(shownModels()).toEqual(expected));
    });

    it("says when nothing matches", async () => {
      renderTab(ADMIN);

      fireEvent.change(
        screen.getByPlaceholderText("Search models or providers"),
        {
          target: { value: "zzz" },
        },
      );

      expect(await screen.findByText("No matching results")).toBeVisible();
    });

    it("filters by type from the filter chips", () => {
      withFilters([
        {
          id: "f1",
          field: "type",
          type: "string",
          operator: "=",
          value: "custom",
        },
      ]);
      renderTab(ADMIN);

      expect(shownModels()).toEqual(["acme-llama-70b"]);
      expect(screen.getByRole("button", { name: /^Type/ })).toHaveTextContent(
        "Custom",
      );
    });

    it("starts on Not default when the organization has its own prices", async () => {
      window.localStorage.removeItem("cost-center:type-default-applied");
      renderTab(ADMIN);

      await waitFor(() =>
        expect(shownModels()).toEqual([
          "acme-llama-70b",
          "gpt-4o",
          "team-model",
        ]),
      );
      expect(screen.getByRole("button", { name: /^Type/ })).toHaveTextContent(
        "Not default",
      );
    });

    it("keeps the type filter cleared once the user clears it", async () => {
      window.localStorage.removeItem("cost-center:type-default-applied");
      const { unmount } = renderTab(ADMIN);
      await waitFor(() => expect(shownModels()).toHaveLength(3));

      fireEvent.click(screen.getByTestId("filter-chips-clear-all"));
      await waitFor(() => expect(shownModels()).toHaveLength(5));

      unmount();
      window.history.replaceState({}, "", "/");
      renderTab(ADMIN);

      expect(shownModels()).toHaveLength(5);
    });

    it("shows every price when the organization has none of its own", () => {
      window.localStorage.removeItem("cost-center:type-default-applied");
      listState.current = { version: 0, rows: [] };
      renderTab(ADMIN);

      expect(shownModels()).toEqual([
        "claude-sonnet-4-5",
        "gpt-4o",
        "gpt-4o-mini",
      ]);
    });

    it("shows everything the organization changed with Not default", () => {
      withFilters([
        {
          id: "f1",
          field: "type",
          type: "string",
          operator: "=",
          value: "not_default",
        },
      ]);
      renderTab(ADMIN);

      expect(shownModels()).toEqual(["acme-llama-70b", "gpt-4o", "team-model"]);
    });

    it("picks a type from the Type chip list", async () => {
      renderTab(ADMIN);

      fireEvent.pointerDown(
        screen.getByRole("button", { name: /^Type/ }),
        new PointerEvent("pointerdown", { bubbles: true, button: 0 }),
      );
      fireEvent.click(
        await screen.findByRole("menuitem", { name: "Override" }),
      );

      await waitFor(() => expect(shownModels()).toEqual(["gpt-4o"]));
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
        {
          target: { value: "gpt-4o" },
        },
      );

      await waitFor(() =>
        expect(shownModels()).toEqual(["gpt-4o", "gpt-4o-mini"]),
      );
    });
  });

  describe("pinning", () => {
    it("shows pinned rows first and remembers them", () => {
      const { unmount } = renderTab(ADMIN);

      fireEvent.click(pinButton("gpt-4o-mini"));

      expect(shownModels()[0]).toBe("gpt-4o-mini");
      expect(pinButton("gpt-4o-mini")).toHaveAttribute("aria-pressed", "true");
      expect(screen.queryByRole("dialog")).toBeNull();

      unmount();
      renderTab(ADMIN);

      expect(shownModels()[0]).toBe("gpt-4o-mini");
      fireEvent.click(pinButton("gpt-4o-mini"));
      expect(shownModels()).toEqual([
        "acme-llama-70b",
        "claude-sonnet-4-5",
        "gpt-4o",
        "gpt-4o-mini",
        "team-model",
      ]);
    });

    it("keeps pinned rows first when the sort flips", () => {
      renderTab(ADMIN);

      fireEvent.click(pinButton("gpt-4o"));
      sortByModel();

      expect(shownModels().slice(0, 2)).toEqual(["gpt-4o", "team-model"]);
    });

    it("leaves out a pinned row that the search doesn't match", async () => {
      renderTab(ADMIN);

      fireEvent.click(pinButton("gpt-4o"));
      fireEvent.change(
        screen.getByPlaceholderText("Search models or providers"),
        {
          target: { value: "sonnet" },
        },
      );

      await waitFor(() =>
        expect(shownModels()).toEqual(["claude-sonnet-4-5", "team-model"]),
      );
    });

    it("pins the open model from the drawer header", () => {
      renderTab(ADMIN);

      openRow("gpt-4o-mini");
      fireEvent.click(screen.getByRole("button", { name: "Pin to the top" }));

      expect(screen.getByRole("button", { name: "Unpin" })).toHaveAttribute(
        "aria-pressed",
        "true",
      );

      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      expect(shownModels()[0]).toBe("gpt-4o-mini");
    });
  });

  describe("row actions", () => {
    const rowAction = (model: string, name: RegExp) =>
      within(rowOf(model)).queryByRole("button", { name });
    const sentModels = () =>
      sentRows().map((row: { model: string }) => row.model);

    it("deletes a custom model after confirming", async () => {
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun),
      );
      renderTab(ADMIN);

      fireEvent.click(rowAction("acme-llama-70b", /^Delete/)!);
      fireEvent.click(
        await screen.findByRole("button", { name: "Delete model" }),
      );

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(mutateAsync.mock.calls[0][0].dryRun).toBe(true);
      expect(sentModels()).toEqual(["gpt-4o", "team-model"]);
      expect(screen.queryByLabelText("Model name")).toBeNull();
    });

    it("restores the defaults of an override from its row", async () => {
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun),
      );
      renderTab(ADMIN);

      fireEvent.click(rowAction("gpt-4o", /^Restore all defaults/)!);
      fireEvent.click(
        await screen.findByRole("button", { name: "Restore all defaults" }),
      );

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(sentModels()).toEqual(["acme-llama-70b", "team-model"]);
    });

    it("offers no row action for built-in prices or to read-only users", () => {
      const { unmount } = renderTab(ADMIN);

      expect(rowAction("gpt-4o-mini", /^(Delete|Restore)/)).toBeNull();

      unmount();
      renderTab({ canEdit: false, isPending: false, organizationName: "Acme" });

      expect(rowAction("acme-llama-70b", /^Delete/)).toBeNull();
    });
  });

  describe("stepping through models", () => {
    const drawerTitle = () =>
      screen.getByRole("dialog").querySelector("h2")?.textContent;

    it("opens the next and previous model in the same drawer", () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.click(screen.getByRole("button", { name: /^Next/ }));

      expect(drawerTitle()).toBe("gpt-4o-mini");

      fireEvent.click(screen.getByRole("button", { name: /^Previous/ }));

      expect(drawerTitle()).toBe("gpt-4o");
    });

    it("follows the on-screen order of pinned rows", () => {
      renderTab(ADMIN);

      fireEvent.click(pinButton("team-model"));
      fireEvent.click(pinButton("gpt-4o"));
      expect(shownModels().slice(0, 2)).toEqual(["team-model", "gpt-4o"]);

      openRow("team-model");
      fireEvent.click(screen.getByRole("button", { name: /^Next/ }));

      expect(drawerTitle()).toBe("gpt-4o");
    });

    it("can't step past the first row of the page", () => {
      renderTab(ADMIN);

      openRow("acme-llama-70b");

      expect(screen.getByRole("button", { name: /^Previous/ })).toBeDisabled();
      expect(screen.getByRole("button", { name: /^Next/ })).toBeEnabled();
    });

    it("asks before leaving unsaved edits for the next model", async () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.change(priceInput("Input"), { target: { value: "3" } });
      fireEvent.click(screen.getByRole("button", { name: /^Next/ }));

      fireEvent.click(await screen.findByRole("button", { name: "Discard" }));

      await waitFor(() => expect(drawerTitle()).toBe("gpt-4o-mini"));
    });

    it("steps to the next model with the K key", () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.keyDown(document.body, { key: "k", code: "KeyK" });

      expect(drawerTitle()).toBe("gpt-4o-mini");
    });
  });

  describe("editing one model", () => {
    it("offers own price or alias only when adding a model", () => {
      const { unmount } = renderTab(ADMIN);

      openRow("acme-llama-70b");
      expect(screen.queryByRole("radio", { name: "Alias" })).toBeNull();

      unmount();
      renderTab(ADMIN);
      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));

      expect(screen.getByRole("radio", { name: "Own price" })).toBeChecked();
      expect(screen.getByRole("radio", { name: "Alias" })).toBeInTheDocument();
    });

    it("names the provider, with its logo when the app has one", () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      const known = within(screen.getByRole("dialog")).getByText("Provider:");
      expect(known).toHaveTextContent(/^Provider:\s*openai$/);
      expect(known.querySelector("svg")).not.toBeNull();

      openRow("team-model");
      const unknown = within(screen.getByRole("dialog")).getByText("Provider:");
      expect(unknown).toHaveTextContent(/^Provider:\s*gateway$/);
      expect(unknown.querySelector("svg")).toBeNull();
    });

    it("keeps focus in the alias fields when their list first opens", async () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      const alias = screen.getByRole("radio", { name: "Alias" });
      act(() => alias.focus());
      fireEvent.click(alias);
      const onProvider = screen.getByLabelText("On provider");
      act(() => onProvider.focus());

      expect(
        await screen.findByRole("option", { name: "openai" }),
      ).toBeInTheDocument();
      expect(onProvider).toHaveFocus();
    });

    it("shows the cost formula with the prices that will be used", () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      expect(screen.queryByTestId("price-formula")).toBeNull();
      fireEvent.click(
        screen.getByRole("button", { name: "How the cost is calculated" }),
      );
      const formula = screen.getByTestId("price-formula");
      expect(formula).toHaveTextContent(
        "cost=Tin·$2.00+Tout·$10.00+Tcr·$1.25+Tcw·$2.001,000,000",
      );
      expect(formula).toHaveTextContent(/Tin\s*input tokens, not cached\s*T/);
      expect(formula).toHaveTextContent(
        /Tout\s*output tokens · built-in price/,
      );
      expect(formula).toHaveTextContent(
        /Tcw\s*cache write tokens · billed at the input price/,
      );

      fireEvent.change(priceInput("Input"), { target: { value: "3" } });
      expect(formula).toHaveTextContent(
        "cost=Tin·$3.00+Tout·$10.00+Tcr·$1.25+Tcw·$3.001,000,000",
      );
    });

    it("prices a new model's empty fields at $0 or the input price", () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      fireEvent.change(priceInput("Input"), { target: { value: "1" } });
      fireEvent.click(
        screen.getByRole("button", { name: "How the cost is calculated" }),
      );
      const formula = screen.getByTestId("price-formula");
      expect(formula).toHaveTextContent(
        "cost=Tin·$1.00+Tout·$0+Tcr·$1.00+Tcw·$1.001,000,000",
      );
      expect(formula).toHaveTextContent(/Tout\s*output tokens · no price set/);
    });

    it("says which cache prices fall back to the input price", () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      expect(
        screen.getByText(
          "Cache reads and writes are billed at the input price unless you add their own price.",
        ),
      ).toBeVisible();

      fireEvent.click(screen.getByRole("button", { name: /Add price/ }));
      fireEvent.change(priceInput("Cache read"), { target: { value: "0.1" } });
      expect(
        screen.getByText(
          "Cache writes are billed at the input price unless you add a cache write price.",
        ),
      ).toBeVisible();
    });

    it("explains an empty provider instead of listing it", async () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      const provider = screen.getByLabelText("Provider");
      fireEvent.focus(provider);
      expect(
        await screen.findByRole("option", { name: "openai" }),
      ).toBeInTheDocument();
      expect(screen.queryByRole("option", { name: "Any provider" })).toBeNull();
      expect(screen.getByText("Empty = any provider")).toBeVisible();
      fireEvent.keyDown(provider, { key: "Escape" });

      fireEvent.click(screen.getByRole("radio", { name: "Alias" }));
      fireEvent.focus(screen.getByLabelText("On provider"));
      expect(
        await screen.findByRole("option", { name: "openai" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("option", { name: "The span's own provider" }),
      ).toBeNull();
      expect(screen.getByText("Empty = the span's own provider")).toBeVisible();
    });

    it("suggests model names for the chosen provider and keeps a new one", async () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      const provider = screen.getByLabelText("Provider");
      fireEvent.focus(provider);
      fireEvent.click(await screen.findByRole("option", { name: "openai" }));
      const modelName = screen.getByLabelText("Model name");
      fireEvent.focus(modelName);
      expect(
        await screen.findByRole("option", { name: "gpt-4o-mini" }),
      ).toBeInTheDocument();
      expect(
        screen.queryByRole("option", { name: "claude-sonnet-4-5" }),
      ).toBeNull();

      fireEvent.keyDown(modelName, { key: "a" });
      fireEvent.change(modelName, { target: { value: "acme-finetune-v3" } });
      expect(
        await screen.findByText(
          "Not in the list. Will be saved as a custom model.",
        ),
      ).toBeInTheDocument();
    });

    it("says an unlisted alias target is checked for a price", async () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      fireEvent.click(screen.getByRole("radio", { name: "Alias" }));
      const pricedLike = screen.getByLabelText("Priced like");
      fireEvent.focus(pricedLike);
      fireEvent.keyDown(pricedLike, { key: "z" });
      fireEvent.change(pricedLike, { target: { value: "zzz-unknown" } });

      expect(
        await screen.findByText(
          "Not in the list. Saving checks that this model has a price.",
        ),
      ).toBeInTheDocument();
    });

    it("suggests models to alias, narrowed to the chosen provider", async () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      fireEvent.click(screen.getByRole("radio", { name: "Alias" }));
      const pricedLike = screen.getByLabelText("Priced like");
      fireEvent.focus(pricedLike);
      expect(
        await screen.findByRole("option", { name: "gpt-4o" }),
      ).toBeInTheDocument();
      expect(
        screen.getByRole("option", { name: "claude-sonnet-4-5" }),
      ).toBeInTheDocument();

      const onProvider = screen.getByLabelText("On provider");
      fireEvent.focus(onProvider);
      fireEvent.click(await screen.findByRole("option", { name: "anthropic" }));
      fireEvent.focus(pricedLike);
      await waitFor(() =>
        expect(screen.queryByRole("option", { name: "gpt-4o" })).toBeNull(),
      );
      expect(
        screen.getByRole("option", { name: "claude-sonnet-4-5" }),
      ).toBeInTheDocument();
    });

    it("suggests known providers and keeps a typed custom one", async () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      const provider = screen.getByLabelText("Provider");
      fireEvent.focus(provider);
      fireEvent.change(provider, { target: { value: "open" } });
      fireEvent.click(await screen.findByRole("option", { name: "openai" }));
      expect(provider).toHaveValue("openai");

      fireEvent.keyDown(provider, { key: "m" });
      fireEvent.change(provider, { target: { value: "my-gateway" } });
      expect(
        await screen.findByText(/Will be saved as a custom provider/),
      ).toBeInTheDocument();
      expect(provider).toHaveValue("my-gateway");
    });

    it("shows no type or provider tags while adding a new model", () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      fireEvent.change(screen.getByLabelText("Provider"), {
        target: { value: "acme" },
      });

      const drawer = screen.getByRole("dialog");
      expect(within(drawer).queryByText("Custom")).toBeNull();
      expect(within(drawer).queryByText("acme", { exact: true })).toBeNull();
    });

    it("closes on the first Escape from the first focused control", async () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      expect(screen.getByRole("button", { name: "Close" })).toHaveFocus();
      fireEvent.keyDown(document.activeElement!, { key: "Escape" });

      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });

    it("gives focus back to Add model once the drawer closes", async () => {
      renderTab(ADMIN);

      const addModel = screen.getByRole("button", { name: /Add model/ });
      addModel.focus();
      fireEvent.click(addModel);
      expect(addModel).not.toHaveFocus();
      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

      await waitFor(() => expect(addModel).toHaveFocus());
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

    it("closes on a click outside", async () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      await clickOutside();

      await waitFor(() =>
        expect(
          screen.queryByRole("button", { name: "Save changes" }),
        ).toBeNull(),
      );
    });

    it("asks before a click outside throws away unsaved edits", async () => {
      renderTab(ADMIN);

      openRow("gpt-4o");
      fireEvent.change(priceInput("Input"), { target: { value: "1.9" } });
      await clickOutside();

      fireEvent.click(
        await screen.findByRole("button", { name: "Keep editing" }),
      );

      expect(priceInput("Input")).toHaveValue("1.9");
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
      fireEvent.click(screen.getByRole("button", { name: /Delete model/ }));

      expect(mutateAsync).not.toHaveBeenCalled();
      fireEvent.click(
        within(
          screen.getByRole("dialog", { name: "Delete acme-llama-70b" }),
        ).getByRole("button", { name: "Delete model" }),
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

    describe("when someone else changes the open model", () => {
      const withAcme = (version: number, changes: object) => ({
        version,
        rows: LIST.rows.map((row) =>
          row.model === "acme-llama-70b" ? { ...row, ...changes } : row,
        ),
      });
      const sentAcme = () =>
        sentRows().find(
          (row: { model: string }) => row.model === "acme-llama-70b",
        );

      it("loads their change and keeps only my edit on top when the list reloads", async () => {
        mutateAsync.mockImplementation(async ({ dryRun }) =>
          replaceResult(dryRun),
        );
        const view = renderTab(ADMIN);

        openRow("acme-llama-70b");
        fireEvent.change(priceInput("Output"), { target: { value: "5" } });
        listState.current = withAcme(4, { input_price: "2" });
        view.rerender(tabTree(ADMIN));

        expect(priceInput("Input")).toHaveValue("2");
        expect(priceInput("Output")).toHaveValue("5");
        expect(
          screen.getByTestId("model-price-changed-elsewhere"),
        ).toHaveTextContent("version 4");

        fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

        await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
        expect(mutateAsync.mock.lastCall![0].request.expected_version).toBe(4);
        expect(sentAcme()).toEqual({
          model: "acme-llama-70b",
          provider: "",
          kind: "fixed",
          input_price: "2",
          output_price: "5",
        });
      });

      it("after a conflict, shows their prices with only my edit on top and saves that", async () => {
        mutateAsync.mockRejectedValueOnce({ response: { status: 409 } });
        refetch.mockResolvedValue({});
        const view = renderTab(ADMIN);

        openRow("acme-llama-70b");
        fireEvent.change(screen.getByLabelText("Note"), {
          target: { value: "Q4 contract" },
        });
        fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
        await waitFor(() => expect(refetch).toHaveBeenCalled());
        listState.current = withAcme(4, { output_price: "6" });
        view.rerender(tabTree(ADMIN));

        expect(priceInput("Output")).toHaveValue("6");
        expect(screen.getByLabelText("Note")).toHaveValue("Q4 contract");

        mutateAsync.mockImplementation(async ({ dryRun }) =>
          replaceResult(dryRun),
        );
        fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

        await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(3));
        expect(mutateAsync.mock.lastCall![0].request.expected_version).toBe(4);
        expect(sentAcme()).toEqual({
          model: "acme-llama-70b",
          provider: "",
          kind: "fixed",
          input_price: "0.6",
          output_price: "6",
          note: "Q4 contract",
        });
      });

      it("says so when someone removed the open model", () => {
        const view = renderTab(ADMIN);

        openRow("acme-llama-70b");
        listState.current = {
          version: 4,
          rows: LIST.rows.filter((row) => row.model !== "acme-llama-70b"),
        };
        view.rerender(tabTree(ADMIN));

        expect(
          screen.getByTestId("model-price-changed-elsewhere"),
        ).toHaveTextContent("removed");
        expect(
          screen.queryByRole("button", { name: /Delete model/ }),
        ).toBeNull();
      });

      it("shows built-in prices and override actions when an alias becomes its own price", () => {
        listState.current = {
          version: 3,
          rows: [
            ...LIST.rows,
            {
              id: "row-4",
              model: "gpt-4o-mini",
              provider: "openai",
              kind: "alias" as const,
              alias_of: { model: "gpt-4o", provider: "openai" },
            },
          ],
        };
        const view = renderTab(ADMIN);
        openRow("gpt-4o-mini");
        listState.current = {
          version: 4,
          rows: [
            ...LIST.rows,
            {
              id: "row-4",
              model: "gpt-4o-mini",
              provider: "openai",
              kind: "fixed" as const,
              input_price: "0.1",
            },
          ],
        };
        view.rerender(tabTree(ADMIN));

        expect(priceInput("Input")).toHaveValue("0.1");
        expect(priceInput("Output")).toHaveValue("0.6");
        expect(
          screen.getByRole("button", { name: /Restore all defaults/ }),
        ).toBeVisible();
        expect(
          screen.queryByRole("button", { name: /Delete model/ }),
        ).toBeNull();
      });

      it("offers to remove, not restore, when an override becomes an alias", () => {
        const view = renderTab(ADMIN);
        openRow("gpt-4o");
        listState.current = {
          version: 4,
          rows: LIST.rows.map((row) =>
            row.model === "gpt-4o"
              ? {
                  id: "row-1",
                  model: "gpt-4o",
                  provider: "openai",
                  kind: "alias" as const,
                  alias_of: { model: "gpt-4.1", provider: "openai" },
                }
              : row,
          ),
        };
        view.rerender(tabTree(ADMIN));

        expect(screen.getByLabelText("Priced like")).toHaveValue("gpt-4.1");
        expect(
          screen.getByRole("button", { name: /Delete model/ }),
        ).toBeVisible();
        expect(
          screen.queryByRole("button", { name: /Restore all defaults/ }),
        ).toBeNull();
      });

      it("leaves the open model alone when another model changes", () => {
        const view = renderTab(ADMIN);

        openRow("acme-llama-70b");
        fireEvent.change(priceInput("Output"), { target: { value: "5" } });
        listState.current = {
          version: 4,
          rows: LIST.rows.map((row) =>
            row.model === "gpt-4o" ? { ...row, input_price: "3" } : row,
          ),
        };
        view.rerender(tabTree(ADMIN));

        expect(priceInput("Input")).toHaveValue("0.6");
        expect(priceInput("Output")).toHaveValue("5");
        expect(
          screen.queryByTestId("model-price-changed-elsewhere"),
        ).toBeNull();
      });
    });

    it("won't add a second row for a model the organization already prices", () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      typeModelName("ACME-llama-70b");
      fireEvent.change(priceInput("Input"), { target: { value: "1" } });
      fireEvent.click(screen.getByRole("button", { name: "Add model" }));

      expect(
        screen.getByText(/This model already has an organization price/),
      ).toBeVisible();
      expect(mutateAsync).not.toHaveBeenCalled();
    });
  });

  describe("adding a custom model", () => {
    it("saves a new custom price without asking about its provider", async () => {
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

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      typeModelName("acme-finetune-v3");
      fireEvent.change(priceInput("Input"), { target: { value: "2" } });
      fireEvent.change(priceInput("Output"), { target: { value: "8" } });
      fireEvent.click(screen.getByRole("button", { name: "Add model" }));

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(mutateAsync.mock.lastCall![0].dryRun).toBe(false);
      expect(screen.queryByTestId("model-price-warnings")).toBeNull();
    });

    it("treats a typed Any provider as the empty provider", async () => {
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun),
      );
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Add model/ }));
      typeModelName("acme-finetune-v3");
      const provider = screen.getByLabelText("Provider");
      fireEvent.focus(provider);
      fireEvent.change(provider, { target: { value: "any provider" } });
      fireEvent.blur(provider);
      fireEvent.change(priceInput("Input"), { target: { value: "2" } });
      fireEvent.click(screen.getByRole("button", { name: "Add model" }));

      await waitFor(() => expect(mutateAsync).toHaveBeenCalledTimes(2));
      expect(sentRows()).toContainEqual(
        expect.objectContaining({ model: "acme-finetune-v3", provider: "" }),
      );
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

    it("says there is nothing to save when every uploaded model stays the same", async () => {
      mutateAsync.mockImplementation(async ({ dryRun }) =>
        replaceResult(dryRun),
      );
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Batch update/ }));
      fireEvent.change(screen.getByRole("textbox"), {
        target: { value: UPLOAD },
      });
      fireEvent.click(screen.getByRole("button", { name: "Preview changes" }));

      expect(await screen.findByTestId("batch-preview")).toHaveTextContent(
        "2 unchanged",
      );
      expect(
        screen.getByRole("button", { name: "Nothing to save" }),
      ).toBeDisabled();
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

    it("closes on a click outside, asking first when something is pasted", async () => {
      renderTab(ADMIN);

      fireEvent.click(screen.getByRole("button", { name: /Batch update/ }));
      fireEvent.change(screen.getByRole("textbox"), {
        target: { value: UPLOAD },
      });
      await clickOutside();
      fireEvent.click(
        await screen.findByRole("button", { name: "Keep editing" }),
      );
      fireEvent.change(screen.getByRole("textbox"), { target: { value: "" } });
      await clickOutside();

      await waitFor(() =>
        expect(screen.queryByText("Batch update prices")).toBeNull(),
      );
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
