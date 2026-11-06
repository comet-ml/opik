import React, { useMemo, useRef, useState } from "react";
import { Layers, Plus } from "lucide-react";
import useModelPrices from "@/api/model-prices/useModelPrices";
import useModelPriceHistory from "@/api/model-prices/useModelPriceHistory";
import useModelsUsed from "@/api/model-prices/useModelsUsed";
import { useModelPricesAccess } from "@/contexts/ModelPricesAccessContext";
import { useActiveWorkspaceName } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";
import SearchInput from "@/shared/SearchInput/SearchInput";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import { Button } from "@/ui/button";
import {
  CATALOG_TYPES,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  CatalogType,
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
import CheckboxFilter from "./CheckboxFilter";
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

const identityKey = (model: string, provider: string) =>
  identityOf({ model, provider, kind: "fixed" });

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
  const [providers, setProviders] = useState<string[]>([]);
  const [types, setTypes] = useState<CatalogType[]>([]);
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

  const visible = useMemo(
    () =>
      sortCatalog(
        filterCatalog(catalog, { search, providers, types }),
        sortDirection,
      ),
    [catalog, search, providers, types, sortDirection],
  );
  const pageEntries = useMemo(
    () => visible.slice((page - 1) * size, page * size),
    [visible, page, size],
  );
  const providerOptions = useMemo(
    () => providersOf(catalog).map((value) => ({ value, label: value })),
    [catalog],
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

  const quickAccess = useMemo(() => {
    const used = (modelsUsed?.content ?? [])
      .filter((model) => model.price_source !== "none")
      .map((model) => byIdentity.get(identityKey(model.model, model.provider)))
      .filter((entry): entry is CatalogEntry => Boolean(entry));
    const defaults = QUICK_ACCESS_DEFAULTS.map((item) =>
      byIdentity.get(identityKey(item.model, item.provider)),
    ).filter((entry): entry is CatalogEntry => Boolean(entry));
    const seen = new Set<string>();
    return [...used, ...defaults]
      .filter((entry) => !seen.has(entry.key) && seen.add(entry.key))
      .slice(0, 4);
  }, [modelsUsed, byIdentity]);

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

      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <SearchInput
            searchText={search}
            setSearchText={resetPage(setSearch)}
            className="w-[280px]"
            placeholder="Search models or providers"
            dimension="sm"
          />
          <CheckboxFilter
            label="Provider"
            options={providerOptions}
            selected={providers}
            onChange={resetPage(setProviders)}
            searchable
          />
          <CheckboxFilter
            label="Type"
            options={CATALOG_TYPES.map((value) => ({
              value,
              label: CATALOG_TYPE_LABELS[value],
            }))}
            selected={types}
            onChange={resetPage(setTypes)}
          />
        </div>
        {canEdit && (
          <div className="flex gap-2">
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
