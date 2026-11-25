import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import useLocalStorageState from "use-local-storage-state";
import { JsonParam } from "use-query-params";
import { ColumnSort, OnChangeFn } from "@tanstack/react-table";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import partition from "lodash/partition";
import { History as HistoryIcon, Layers, Plus } from "lucide-react";
import useModelPrices from "@/api/model-prices/useModelPrices";
import useModelPriceHistory from "@/api/model-prices/useModelPriceHistory";
import { useModelPricesAccess } from "@/contexts/ModelPricesAccessContext";
import Loader from "@/shared/Loader/Loader";
import SearchInput from "@/shared/SearchInput/SearchInput";
import FilterChipBar from "@/shared/filter-chips/FilterChipBar/FilterChipBar";
import useFilterChips from "@/shared/filter-chips/hooks/useFilterChips";
import { ChipDefinition, chipOptionsValue } from "@/shared/filter-chips/types";
import { COLUMN_TYPE } from "@/types/shared";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import ExplainerCallout from "@/shared/ExplainerCallout/ExplainerCallout";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import { Button } from "@/ui/button";
import {
  CATALOG_TYPES,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  NOT_DEFAULT_LABEL,
  NOT_DEFAULT_TYPE,
  buildCatalog,
  filterCatalog,
  builtInLookup,
  groupHistory,
  providersOf,
  sortCatalog,
} from "@/lib/model-prices";
import CostCenterHeader from "./CostCenterHeader";
import HistoryPanel from "./HistoryPanel";
import PricesTable, {
  DEFAULT_COLUMNS_ORDER,
  DEFAULT_SELECTED_COLUMNS,
  PRICE_COLUMNS,
} from "./PricesTable";
import ModelPriceDrawer, { DrawerTarget } from "./ModelPriceDrawer";
import BatchUpdateDrawer from "./BatchUpdateDrawer";
import useBuiltInPrices from "./useBuiltInPrices";

const HISTORY_PAGE = { page: 1, size: 100 };
const PAGE_SIZES = [25, 50, 100];

const PINNED_STORAGE_KEY = "cost-center:pinned-models";
const SELECTED_COLUMNS_KEY = "cost-center:selected-columns";
const COLUMNS_ORDER_KEY = "cost-center:columns-order";
const COLUMNS_WIDTH_KEY = "cost-center:columns-width";
const COLUMNS_SORT_KEY = "cost-center:columns-sort";
const FILTERS_KEY = "cost-center:filters";
const TYPE_DEFAULT_APPLIED_KEY = "cost-center:type-default-applied";
const DEFAULT_SORTING: ColumnSort[] = [{ id: "model", desc: false }];
const IS_LABEL = { "=": "is" } as const;

const CostCenterTab: React.FC = () => {
  const access = useModelPricesAccess();
  const canEdit = access.canEdit && !access.isPending;
  const { data: list, isPending, isError } = useModelPrices();
  const { data: builtIns, isPending: isBuiltInPending } = useBuiltInPrices();
  const { data: historyPage } = useModelPriceHistory(HISTORY_PAGE);
  const history = useMemo(
    () =>
      groupHistory(historyPage?.content ?? [], builtInLookup(builtIns ?? [])),
    [historyPage, builtIns],
  );

  const [search, setSearch] = useState("");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(PAGE_SIZES[0]);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [drawerTarget, setDrawerTarget] = useState<DrawerTarget>();
  const [isBatchOpen, setIsBatchOpen] = useState(false);
  const drawerKey = useRef(0);
  const returnFocusTo = useRef<HTMLElement | null>(null);
  const isAnyDrawerOpen = Boolean(drawerTarget) || isBatchOpen;

  // Radix hands focus back only to its own trigger, and these drawers are
  // opened without one, so without this focus falls to <body>.
  useEffect(() => {
    if (isAnyDrawerOpen) return;
    returnFocusTo.current?.focus();
    returnFocusTo.current = null;
  }, [isAnyDrawerOpen]);

  const rememberFocus = () => {
    returnFocusTo.current =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
  };

  const catalog = useMemo(
    () => buildCatalog(builtIns ?? [], list?.rows ?? []),
    [builtIns, list],
  );
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
        operatorLabels: IS_LABEL,
        hideOperatorInSummary: true,
        addLabel: "Add provider",
        value: {
          placeholder: "Select a provider…",
          optionsHeading: "Providers",
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
        kind: "single-select",
        columnType: COLUMN_TYPE.string,
        operator: "=",
        options: [
          { value: "default", label: CATALOG_TYPE_LABELS.default },
          { value: NOT_DEFAULT_TYPE, label: NOT_DEFAULT_LABEL },
          ...CATALOG_TYPES.filter((type) => type !== "default").map((type) => ({
            value: type,
            label: CATALOG_TYPE_LABELS[type],
          })),
        ],
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
    persistKey: FILTERS_KEY,
  });

  // Applied once per browser: after that the remembered filters are the
  // user's own choice, including clearing this default.
  const [isTypeDefaultApplied, setIsTypeDefaultApplied] =
    useLocalStorageState<boolean>(TYPE_DEFAULT_APPLIED_KEY, {
      defaultValue: false,
    });
  const hasOrganizationPrices = Boolean(list?.rows.length);
  const hasFilters = chips.filters.length > 0;
  const { applyValue } = chips;
  useEffect(() => {
    if (isTypeDefaultApplied || !hasOrganizationPrices || hasFilters) return;
    setIsTypeDefaultApplied(true);
    applyValue("type", { value: NOT_DEFAULT_TYPE });
  }, [
    isTypeDefaultApplied,
    hasOrganizationPrices,
    hasFilters,
    applyValue,
    setIsTypeDefaultApplied,
  ]);

  const [pinned = [], setPinned] = useLocalStorageState<string[]>(
    PINNED_STORAGE_KEY,
    { defaultValue: [] },
  );
  const pinnedSet = useMemo(() => new Set(pinned), [pinned]);
  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    { defaultValue: DEFAULT_SELECTED_COLUMNS },
  );
  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    { defaultValue: DEFAULT_COLUMNS_ORDER },
  );
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, { defaultValue: {} });
  const isPinned = useCallback(
    (entry: CatalogEntry) => pinnedSet.has(entry.key),
    [pinnedSet],
  );
  const togglePin = useCallback(
    (entry: CatalogEntry) =>
      setPinned((current = []) =>
        current.includes(entry.key)
          ? current.filter((key) => key !== entry.key)
          : [...current, entry.key],
      ),
    [setPinned],
  );

  const [sorting, setSorting] = useQueryParamAndLocalStorageState<ColumnSort[]>(
    {
      localStorageKey: COLUMNS_SORT_KEY,
      queryKey: "prices_sorting",
      defaultValue: DEFAULT_SORTING,
      queryParamConfig: JsonParam,
    },
  );
  const sortDirection = sorting[0]?.desc ? "desc" : "asc";
  const changeSorting: OnChangeFn<ColumnSort[]> = (updater) => {
    setSorting(updater);
    setPage(1);
  };

  // DataTable lifts pinned rows only within the page it is given, so they are
  // moved to the front before paging to land on the first page.
  const visible = useMemo(() => {
    const sorted = sortCatalog(
      filterCatalog(catalog, { search, filters: chips.filters }),
      sortDirection,
      search,
    );
    return partition(sorted, isPinned).flat();
  }, [catalog, search, chips.filters, sortDirection, isPinned]);
  const pageEntries = useMemo(
    () => visible.slice((page - 1) * size, page * size),
    [visible, page, size],
  );

  const drawerEntry = drawerTarget?.entry;
  const openDrawer = (target: DrawerTarget) => {
    rememberFocus();
    drawerKey.current += 1;
    setDrawerTarget(target);
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
        organizationName={access.organizationName}
        canEdit={canEdit}
        isAccessPending={access.isPending}
        actions={
          canEdit && (
            <div className="flex items-center gap-2">
              {/* The outline's light border blends into the page, so this
                  button is 2px taller to give its white face the same height
                  as the filled Batch update button. */}
              <Button
                variant="outline"
                size="xs"
                className="h-[30px]"
                onClick={() => openDrawer({})}
              >
                <Plus className="mr-1 size-4" />
                Add model
              </Button>
              <Button
                variant="default"
                size="xs"
                onClick={() => {
                  rememberFocus();
                  setIsBatchOpen(true);
                }}
              >
                <Layers className="mr-1 size-4" />
                Batch update
              </Button>
            </div>
          )
        }
      />

      <ExplainerCallout
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_model_prices]}
      />

      <div className="mb-4 flex flex-wrap items-start gap-1.5">
        <div className="min-w-0 flex-[1_1_510px]">
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
                className="w-[320px] shrink-0"
                placeholder="Search models or providers"
                dimension="sm"
              />
            }
          />
        </div>
        <div className="ml-auto flex shrink-0 items-center gap-2">
          <ColumnsButton
            columns={PRICE_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
          {history.length > 0 && (
            <TooltipWrapper
              content={isHistoryOpen ? "Hide history" : "History"}
            >
              <Button
                variant="outline"
                size="icon-sm"
                aria-label="History"
                aria-pressed={isHistoryOpen}
                onClick={() => setIsHistoryOpen((open) => !open)}
              >
                <HistoryIcon className="size-3.5" />
              </Button>
            </TooltipWrapper>
          )}
        </div>
      </div>

      {isHistoryOpen && history.length > 0 && (
        <HistoryPanel history={history} />
      )}

      <PricesTable
        entries={pageEntries}
        selectedColumns={selectedColumns}
        columnsOrder={columnsOrder}
        columnsWidth={columnsWidth}
        onColumnsWidthChange={setColumnsWidth}
        sorting={sorting}
        onSortingChange={changeSorting}
        pinnedKeys={pinned}
        onPinnedKeysChange={setPinned}
        activeKey={drawerEntry?.key}
        onOpen={(entry) => openDrawer({ entry })}
        onClearFilters={() => {
          chips.clearAll();
          resetPage(setSearch)("");
        }}
      />
      {visible.length > 0 && (
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
          isPinned={drawerEntry ? isPinned(drawerEntry) : false}
          onTogglePin={drawerEntry ? () => togglePin(drawerEntry) : undefined}
        />
      )}

      {isBatchOpen && (
        <BatchUpdateDrawer
          open={isBatchOpen}
          setOpen={setIsBatchOpen}
          list={list}
          builtIns={builtIns ?? []}
        />
      )}
    </div>
  );
};

export default CostCenterTab;
