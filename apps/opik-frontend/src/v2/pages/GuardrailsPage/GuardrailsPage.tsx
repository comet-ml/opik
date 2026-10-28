import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnPinningState, ColumnSort } from "@tanstack/react-table";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { Plus } from "lucide-react";

import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import useAppStore from "@/store/AppStore";
import PageEmptyState from "@/shared/PageEmptyState/PageEmptyState";
import { buildDocsUrl } from "@/v2/lib/utils";
import emptyAlertsLightUrl from "/images/empty-alerts-light.svg";
import emptyAlertsDarkUrl from "/images/empty-alerts-dark.svg";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import SearchInput from "@/shared/SearchInput/SearchInput";
import IdCell from "@/shared/DataTableCells/IdCell";
import TagCell from "@/shared/DataTableCells/TagCell";
import TextCell from "@/shared/DataTableCells/TextCell";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import {
  generateActionsColumDef,
  generateSelectColumDef,
  getRowId,
} from "@/shared/DataTable/utils";
import { convertColumnDataToColumn } from "@/lib/table";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import {
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { GuardrailPolicy, GuardrailPolicyRuleType } from "@/types/guardrails";
import {
  GUARDRAIL_RULE_TYPES_ORDER,
  GuardrailExecutionScopeLabelMap,
  GuardrailExecutionScopeTagVariantMap,
} from "@/constants/guardrails";
import useGuardrailPoliciesList from "@/api/guardrail-policies/useGuardrailPoliciesList";
import useGuardrailPolicyById from "@/api/guardrail-policies/useGuardrailPolicyById";
import { GUARDRAILS_POLICY_PARAM } from "@/v2/constants/guardrails";
import RuleTypesCell from "@/v2/pages/GuardrailsPage/RuleTypesCell";
import GuardrailPoliciesActionsPanel from "@/v2/pages/GuardrailsPage/GuardrailPoliciesActionsPanel";
import GuardrailPolicyRowActionsCell from "@/v2/pages/GuardrailsPage/GuardrailPolicyRowActionsCell";
import GuardrailPolicyPanel from "@/v2/pages-shared/guardrails/GuardrailPolicyPanel/GuardrailPolicyPanel";

const DOCS_URL = buildDocsUrl("/production/gateway-guardrails/guardrails");

const SELECTED_COLUMNS_KEY = "guardrail-policies-selected-columns";
const COLUMNS_WIDTH_KEY = "guardrail-policies-columns-width";
const COLUMNS_ORDER_KEY = "guardrail-policies-columns-order";
const COLUMNS_SORT_KEY = "guardrail-policies-columns-sort";
const PAGINATION_SIZE_KEY = "guardrail-policies-pagination-size";

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "description",
  "rules",
  "execution_scope",
  "last_updated_at",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_NAME_ID,
  "description",
  "rules",
  "execution_scope",
  "created_at",
  "created_by",
  "last_updated_at",
];

/** Sorted into one order so two policies with the same rules read the same way. */
const getRuleTypes = (policy: GuardrailPolicy): GuardrailPolicyRuleType[] =>
  GUARDRAIL_RULE_TYPES_ORDER.filter((type) =>
    policy.rules.some((rule) => rule.type === type),
  );

const GuardrailsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [openCreatePanel, setOpenCreatePanel] = useState(false);

  // Which policy is open, in the URL: a policy has no route of its own, so this is what a link to
  // one resolves through.
  const [openedPolicyId, setOpenedPolicyId] = useQueryParam(
    GUARDRAILS_POLICY_PARAM,
    StringParam,
    { updateType: "replaceIn" },
  );

  const columnsDef: ColumnData<GuardrailPolicy>[] = useMemo(
    () => [
      {
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        cell: TextCell as never,
        sortable: true,
      },
      {
        id: "id",
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
      },
      {
        id: "description",
        label: "Description",
        type: COLUMN_TYPE.string,
      },
      {
        id: "rules",
        label: "Rules",
        type: COLUMN_TYPE.list,
        cell: RuleTypesCell as never,
        accessorFn: getRuleTypes,
      },
      {
        id: "execution_scope",
        label: "Execution scope",
        type: COLUMN_TYPE.category,
        cell: TagCell as never,
        accessorFn: (row) =>
          GuardrailExecutionScopeLabelMap[row.execution_scope] ??
          row.execution_scope,
        customMeta: {
          variantMap: GuardrailExecutionScopeTagVariantMap,
        },
      },
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        cell: TimeCell as never,
        sortable: true,
      },
      {
        id: "created_by",
        label: "Created by",
        type: COLUMN_TYPE.string,
      },
      {
        id: "last_updated_at",
        label: "Last updated",
        type: COLUMN_TYPE.time,
        cell: TimeCell as never,
        sortable: true,
      },
    ],
    [],
  );

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });
  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });
  const [rowSelection = {}, setRowSelection] = useQueryParam(
    "selection",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 10,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: COLUMNS_SORT_KEY,
    queryKey: "sorting",
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useGuardrailPoliciesList(
      {
        workspaceName,
        sorting: sortedColumns,
        search: search!,
        page: page!,
        size: size!,
      },
      {
        placeholderData: keepPreviousData,
      },
    );

  const policies = useMemo(() => data?.content ?? [], [data?.content]);
  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );
  const total = data?.total ?? 0;

  const policyOnPage = useMemo(
    () => policies.find((policy) => policy.id === openedPolicyId),
    [policies, openedPolicyId],
  );

  const { data: fetchedPolicy } = useGuardrailPolicyById(
    { policyId: openedPolicyId! },
    { enabled: Boolean(openedPolicyId) && !policyOnPage },
  );

  const openedPolicy = policyOnPage ?? fetchedPolicy;

  const selectedPolicies = useMemo(
    () => policies.filter((policy) => rowSelection[policy.id]),
    [policies, rowSelection],
  );

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: DEFAULT_COLUMNS_ORDER,
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const columns = useMemo(
    () => [
      generateSelectColumDef<GuardrailPolicy>(),
      ...convertColumnDataToColumn<GuardrailPolicy, GuardrailPolicy>(
        columnsDef,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
      generateActionsColumDef({
        cell: GuardrailPolicyRowActionsCell,
      }),
    ],
    [columnsDef, columnsOrder, selectedColumns, sortableBy],
  );

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [sortedColumns, setSortedColumns],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  // There is no detail page: the whole policy fits in the panel, so a row click opens it.
  const handleRowClick = useCallback(
    (policy: GuardrailPolicy) => setOpenedPolicyId(policy.id),
    [setOpenedPolicyId],
  );

  const handleCloseEditPanel = useCallback(
    (open: boolean) => {
      if (!open) setOpenedPolicyId(undefined);
    },
    [setOpenedPolicyId],
  );

  const handleCreateClick = useCallback(() => setOpenCreatePanel(true), []);

  const isTableLoading =
    isPending || (isPlaceholderData && policies.length === 0);
  const isEmpty = !isTableLoading && !search && policies.length === 0;

  return (
    <div className="flex min-h-full flex-col pt-4">
      <div className="mb-4 flex items-center justify-between gap-4">
        <h1 className="comet-body-accented truncate break-words">Guardrails</h1>
        {!isEmpty && (
          <Button variant="default" size="xs" onClick={handleCreateClick}>
            <Plus className="mr-1 size-4" />
            Create policy
          </Button>
        )}
      </div>
      {isEmpty ? (
        <PageEmptyState
          lightImageUrl={emptyAlertsLightUrl}
          darkImageUrl={emptyAlertsDarkUrl}
          title="No policies yet"
          description={
            "A policy is a named group of rules, checks such as PII detection or restricted topics,\nthat your applications reference by name, so the same protection applies everywhere."
          }
          primaryActionLabel="Create your first policy"
          onPrimaryAction={handleCreateClick}
          docsUrl={DOCS_URL}
        />
      ) : (
        <>
          <div className="mb-2 flex items-start gap-2">
            <SearchInput
              searchText={search!}
              setSearchText={setSearch}
              placeholder="Search"
              className="w-[200px] shrink-0"
              dimension="xs"
            ></SearchInput>
            <div className="flex flex-1 shrink-0 items-center justify-end gap-2">
              <GuardrailPoliciesActionsPanel policies={selectedPolicies} />
              <Separator orientation="vertical" className="mx-[2px] h-4" />
              <ColumnsButton
                columns={columnsDef}
                selectedColumns={selectedColumns}
                onSelectionChange={setSelectedColumns}
                order={columnsOrder}
                onOrderChange={setColumnsOrder}
                layout="labeled"
                size="2xs"
              ></ColumnsButton>
              <Separator orientation="vertical" className="mx-[2px] h-4" />
              <RefreshButton
                tooltip="Refresh policies list"
                isFetching={isFetching}
                onRefresh={refetch}
                size="icon-2xs"
              />
            </div>
          </div>
          <DataTable
            columns={columns}
            data={policies}
            onRowClick={handleRowClick}
            sortConfig={sortConfig}
            resizeConfig={resizeConfig}
            selectionConfig={{
              rowSelection,
              setRowSelection,
            }}
            getRowId={getRowId}
            columnPinning={DEFAULT_COLUMN_PINNING}
            noData={<DataTableNoMatchingData />}
            showSkeleton={isTableLoading}
            showLoadingOverlay={
              !isTableLoading && isPlaceholderData && isFetching
            }
          />
          <div className="py-4">
            <DataTablePagination
              page={page!}
              pageChange={setPage}
              size={size!}
              sizeChange={setSize}
              total={total}
            ></DataTablePagination>
          </div>
        </>
      )}
      <GuardrailPolicyPanel
        mode="create"
        open={openCreatePanel}
        setOpen={setOpenCreatePanel}
      />
      {openedPolicy && (
        <GuardrailPolicyPanel
          mode="edit"
          policy={openedPolicy}
          open={Boolean(openedPolicyId)}
          setOpen={handleCloseEditPanel}
        />
      )}
    </div>
  );
};

export default GuardrailsPage;
