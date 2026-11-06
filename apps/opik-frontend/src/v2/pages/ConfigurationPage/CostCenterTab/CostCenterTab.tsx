import React, { useCallback, useMemo, useRef, useState } from "react";
import useLocalStorageState from "use-local-storage-state";
import { Layers, Plus } from "lucide-react";
import useModelPrices from "@/api/model-prices/useModelPrices";
import useModelPriceHistory from "@/api/model-prices/useModelPriceHistory";
import useModelsUsed from "@/api/model-prices/useModelsUsed";
import { useModelPricesAccess } from "@/contexts/ModelPricesAccessContext";
import { useActiveWorkspaceName } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";
import SearchInput from "@/shared/SearchInput/SearchInput";
import FilterChipBar from "@/shared/filter-chips/FilterChipBar/FilterChipBar";
import useFilterChips from "@/shared/filter-chips/hooks/useFilterChips";
import { ChipDefinition, chipOptionsValue } from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import { Button } from "@/ui/button";
import {
  CATALOG_TYPES,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  ModelUsed,
  buildCatalog,
  filterCatalog,
  groupHistory,
  identityOf,
  providersOf,
  sortCatalog,
} from "@/lib/model-prices";
import CostCenterHeader from "./CostCenterHeader";
import CatalogTable from "./CatalogTable";
import ModelPriceDrawer, { DrawerTarget } from "./ModelPriceDrawer";
import BatchUpdateDrawer from "./BatchUpdateDrawer";
import NoPriceBanner from "./NoPriceBanner";
import QuickAccess from "./QuickAccess";
import useBuiltInPrices from "./useBuiltInPrices";

const HISTORY_PAGE = { page: 1, size: 100 };
const PAGE_SIZES = [25, 50, 100];
const QUICK_ACCESS_DEFAULTS = [
  { model: "gpt-5", provider: "openai" },
  { model: "gpt-5-mini", provider: "openai" },
  { model: "claude-sonnet-4-5", provider: "anthropic" },
  { model: "claude-haiku-4-5", provider: "anthropic" },
];

const PINNED_STORAGE_KEY = "cost-center:pinned-models";
const MAX_QUICK_ACCESS = 8;

const identityKey = (model: string, provider: string) =>
  identityOf({ model, provider, kind: "fixed" });

const byMostUsed = (left: ModelUsed, right: ModelUsed) =>
  right.days_used - left.days_used ||
  right.last_seen.localeCompare(left.last_seen);

const CostCenterTab: React.FC = () => {
  const access = useModelPricesAccess();
  const canEdit = access.canEdit && !access.isPending;
  const workspaceName = useActiveWorkspaceName();
  const { data: list, isPending, isError } = useModelPrices();
  const { data: builtIns, isPending: isBuiltInPending } = useBuiltInPrices();
  const { data: modelsUsed } = useModelsUsed();
  const { data: historyPage } = useModelPriceHistory(HISTORY_PAGE);
  const history = useMemo(
    () => groupHistory(historyPage?.content ?? []),
    [historyPage],
  );

  const [search, setSearch] = useState("");
  const [sortDirection, setSortDirection] = useState<"asc" | "desc">("asc");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(PAGE_SIZES[0]);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [drawerTarget, setDrawerTarget] = useState<DrawerTarget>();
  const [isBatchOpen, setIsBatchOpen] = useState(false);
  const drawerKey = useRef(0);

  const catalog = useMemo(
    () => buildCatalog(builtIns ?? [], list?.rows ?? []),
    [builtIns, list],
  );
  const byIdentity = useMemo(() => {
    const map = new Map<string, CatalogEntry>();
    catalog.forEach((entry) =>
      map.set(identityKey(entry.model, entry.provider), entry),
    );
    return map;
  }, [catalog]);

  const providerOptions = useMemo(() => providersOf(catalog), [catalog]);
  const chipDefinitions = useMemo<ChipDefinition[]>(
    () => [
      {
        id: "provider",
        field: "provider",
        label: "Provider",
        kind: "query-builder",
        columnType: COLUMN_TYPE.string,
        operators: ["="],
        defaultOperator: "=",
        addLabel: "Add provider",
        value: {
          placeholder: "Select a provider…",
          options: chipOptionsValue({
            items: providerOptions,
            isLoading: false,
          }),
        },
      },
      {
        id: "type",
        field: "type",
        label: "Type",
        kind: "query-builder",
        columnType: COLUMN_TYPE.string,
        operators: ["="],
        defaultOperator: "=",
        addLabel: "Add type",
        value: {
          placeholder: "Select a type…",
          options: chipOptionsValue({
            items: CATALOG_TYPES.map((type) => CATALOG_TYPE_LABELS[type]),
            isLoading: false,
          }),
        },
      },
      {
        id: "input_price",
        field: "input_price",
        label: "Input price /1M",
        kind: "numeric",
        columnType: COLUMN_TYPE.number,
        format: "currency",
      },
      {
        id: "output_price",
        field: "output_price",
        label: "Output price /1M",
        kind: "numeric",
        columnType: COLUMN_TYPE.number,
        format: "currency",
      },
      {
        id: "cache_prices",
        field: "cache_prices",
        label: "Has cache prices",
        kind: "boolean",
        onOperator: "is_not_empty",
        columnType: COLUMN_TYPE.string,
      },
    ],
    [providerOptions],
  );
  const chips = useFilterChips({
    tableId: "cost-center-prices",
    urlKey: "prices_filters",
    definitions: chipDefinitions,
    defaultPinned: ["provider", "type"],
    onChange: () => setPage(1),
  });

  const visible = useMemo(
    () =>
      sortCatalog(
        filterCatalog(catalog, { search, filters: chips.filters }),
        sortDirection,
        search,
      ),
    [catalog, search, chips.filters, sortDirection],
  );
  const pageEntries = useMemo(
    () => visible.slice((page - 1) * size, page * size),
    [visible, page, size],
  );

  const unpriced = useMemo(() => {
    const all = (modelsUsed?.content ?? []).filter(
      (model) => model.price_source === "none",
    );
    const inWorkspace = all.filter((model) =>
      model.workspaces.some((workspace) => workspace.name === workspaceName),
    );
    return inWorkspace.length
      ? { models: inWorkspace, scope: "workspace" as const }
      : { models: all, scope: "organization" as const };
  }, [modelsUsed, workspaceName]);

  const [pinned = [], setPinned] = useLocalStorageState<string[]>(
    PINNED_STORAGE_KEY,
    { defaultValue: [] },
  );
  const pinnedSet = useMemo(() => new Set(pinned), [pinned]);
  const isPinned = useCallback(
    (entry: CatalogEntry) =>
      pinnedSet.has(identityKey(entry.model, entry.provider)),
    [pinnedSet],
  );
  const togglePin = useCallback(
    (entry: CatalogEntry) => {
      const key = identityKey(entry.model, entry.provider);
      setPinned((current = []) =>
        current.includes(key)
          ? current.filter((item) => item !== key)
          : [...current, key],
      );
    },
    [setPinned],
  );

  const quickAccess = useMemo(() => {
    const pinnedEntries = pinned
      .map((key) => byIdentity.get(key))
      .filter((entry): entry is CatalogEntry => Boolean(entry));
    const used = [...(modelsUsed?.content ?? [])]
      .filter((model) => model.price_source !== "none")
      .sort(byMostUsed)
      .map((model) => byIdentity.get(identityKey(model.model, model.provider)))
      .filter((entry): entry is CatalogEntry => Boolean(entry));
    const defaults = QUICK_ACCESS_DEFAULTS.map((item) =>
      byIdentity.get(identityKey(item.model, item.provider)),
    ).filter((entry): entry is CatalogEntry => Boolean(entry));
    const seen = new Set<string>();
    const limit = Math.min(Math.max(4, pinnedEntries.length), MAX_QUICK_ACCESS);
    return [...pinnedEntries, ...used, ...defaults]
      .filter((entry) => !seen.has(entry.key) && seen.add(entry.key))
      .slice(0, limit);
  }, [pinned, modelsUsed, byIdentity]);

  const openDrawer = (target: DrawerTarget) => {
    drawerKey.current += 1;
    setDrawerTarget(target);
  };

  const openUnpriced = (model: ModelUsed) => {
    const existing = byIdentity.get(identityKey(model.model, model.provider));
    openDrawer(
      existing
        ? { entry: existing }
        : { prefill: { model: model.model, provider: model.provider } },
    );
  };

  const resetPage =
    <T,>(setter: (value: T) => void) =>
    (value: T) => {
      setter(value);
      setPage(1);
    };

  if (isPending || isBuiltInPending) {
    return <Loader />;
  }

  if (isError || !list) {
    return (
      <p className="comet-body-s py-8 text-center text-muted-slate">
        The prices couldn&apos;t be loaded. Refresh the page to try again.
      </p>
    );
  }

  return (
    <div data-testid="cost-center-tabpanel" className="pb-12">
      <CostCenterHeader
        version={list.version}
        organizationName={access.organizationName}
        canEdit={canEdit}
        isAccessPending={access.isPending}
        latest={history[0]}
        history={history}
        isHistoryOpen={isHistoryOpen}
        onToggleHistory={() => setIsHistoryOpen((open) => !open)}
      />

      <NoPriceBanner
        models={unpriced.models}
        scope={unpriced.scope}
        canEdit={canEdit}
        onAddPrices={openUnpriced}
      />

      <QuickAccess
        entries={quickAccess}
        onOpen={(entry) => openDrawer({ entry })}
      />

      <div className="mb-3 flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <FilterChipBar
            chipsPinned={chips.chipsPinned}
            chipsUnpinned={chips.chipsUnpinned}
            values={chips.values}
            managerOpen={chips.managerOpen}
            onManagerOpenChange={chips.setManagerOpen}
            onApplyValue={chips.applyValue}
            onClearValue={chips.clearValue}
            onPinChip={chips.pinChip}
            onUnpinChip={chips.unpinChip}
            onClearAll={chips.clearAll}
            openChipId={chips.openChipId}
            onOpenChipIdChange={chips.setOpenChipId}
            prefix={
              <SearchInput
                searchText={search}
                setSearchText={resetPage(setSearch)}
                className="w-[280px] shrink-0"
                placeholder="Search models or providers"
                dimension="sm"
              />
            }
          />
        </div>
        {canEdit && (
          <div className="ml-auto flex shrink-0 gap-2">
            <Button size="sm" variant="outline" onClick={() => openDrawer({})}>
              <Plus className="mr-1.5 size-3.5" />
              Add model
            </Button>
            <Button size="sm" onClick={() => setIsBatchOpen(true)}>
              <Layers className="mr-1.5 size-3.5" />
              Batch update
            </Button>
          </div>
        )}
      </div>

      {visible.length === 0 ? (
        <p className="comet-body-s rounded-md border py-8 text-center text-muted-slate">
          No prices match these filters.
        </p>
      ) : (
        <>
          <CatalogTable
            entries={pageEntries}
            sortDirection={sortDirection}
            onToggleSort={() =>
              setSortDirection((direction) =>
                direction === "asc" ? "desc" : "asc",
              )
            }
            onOpen={(entry) => openDrawer({ entry })}
            isPinned={isPinned}
            onTogglePin={togglePin}
          />
          <div className="pt-4">
            <DataTablePagination
              page={page}
              pageChange={setPage}
              size={size}
              sizeChange={resetPage(setSize)}
              total={visible.length}
              itemsPerPage={PAGE_SIZES}
            />
          </div>
        </>
      )}

      {drawerTarget && (
        <ModelPriceDrawer
          key={`drawer-${drawerKey.current}`}
          open={Boolean(drawerTarget)}
          setOpen={(open) => !open && setDrawerTarget(undefined)}
          target={drawerTarget}
          list={list}
          builtIns={builtIns ?? []}
          canEdit={canEdit}
        />
      )}

      {isBatchOpen && (
        <BatchUpdateDrawer
          open={isBatchOpen}
          setOpen={setIsBatchOpen}
          list={list}
        />
      )}
    </div>
  );
};

export default CostCenterTab;
