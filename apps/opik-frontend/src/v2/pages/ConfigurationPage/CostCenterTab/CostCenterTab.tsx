import React, { useMemo, useRef, useState } from "react";
import { Plus } from "lucide-react";
import useModelPriceHistory from "@/api/model-prices/useModelPriceHistory";
import { useModelPricesAccess } from "@/contexts/ModelPricesAccessContext";
import Loader from "@/shared/Loader/Loader";
import SearchInput from "@/shared/SearchInput/SearchInput";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import { Button } from "@/ui/button";
import {
  DraftRow,
  ModelPriceProblem,
  ModelPriceRow,
  groupHistory,
  identityOf,
} from "@/lib/model-prices";
import CostCenterHeader from "./CostCenterHeader";
import ModelPriceDialog from "./ModelPriceDialog";
import ModelPricesTable from "./ModelPricesTable";
import ReviewModelPricesDialog from "./ReviewModelPricesDialog";
import useModelPricesEditor from "./useModelPricesEditor";

const HISTORY_PAGE = { page: 1, size: 100 };

const mergeProblems = (
  ...sources: Record<string, ModelPriceProblem[]>[]
): Record<string, ModelPriceProblem[]> => {
  const merged: Record<string, ModelPriceProblem[]> = {};
  sources.forEach((source) =>
    Object.entries(source).forEach(([key, problems]) => {
      merged[key] = [...(merged[key] ?? []), ...problems];
    }),
  );
  return merged;
};

const matchesSearch = (row: ModelPriceRow, search: string) =>
  [row.model, row.provider, row.alias_of?.model, row.note].some(
    (value) => value?.toLowerCase().includes(search),
  );

const describeCounts = (counts: {
  created: number;
  updated: number;
  deleted: number;
}) =>
  [
    counts.created && `${counts.created} added`,
    counts.updated && `${counts.updated} edited`,
    counts.deleted && `${counts.deleted} removed`,
  ]
    .filter(Boolean)
    .join(", ");

const CostCenterTab: React.FC = () => {
  const access = useModelPricesAccess();
  const canEdit = access.canEdit && !access.isPending;
  const {
    draft,
    dispatch,
    clientErrors,
    counts,
    isDirty,
    hasClientErrors,
    isPending,
    isError,
    preview,
    save,
  } = useModelPricesEditor();
  const { data: historyPage } = useModelPriceHistory(HISTORY_PAGE);
  const history = useMemo(
    () => groupHistory(historyPage?.content ?? []),
    [historyPage],
  );

  const [search, setSearch] = useState("");
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [editedRow, setEditedRow] = useState<DraftRow>();
  const [isPriceDialogOpen, setIsPriceDialogOpen] = useState(false);
  const [isReviewOpen, setIsReviewOpen] = useState(false);
  const dialogKeyRef = useRef(0);

  const errors = useMemo(
    () => mergeProblems(clientErrors, draft.errors),
    [clientErrors, draft.errors],
  );

  const visibleRows = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return needle
      ? draft.rows.filter((draftRow) => matchesSearch(draftRow.row, needle))
      : draft.rows;
  }, [draft.rows, search]);

  const takenIdentities = useMemo(
    () =>
      new Set(
        draft.rows
          .filter(
            (draftRow) => !draftRow.deleted && draftRow.key !== editedRow?.key,
          )
          .map((draftRow) => identityOf(draftRow.row)),
      ),
    [draft.rows, editedRow],
  );

  const openPriceDialog = (draftRow?: DraftRow) => {
    dialogKeyRef.current += 1;
    setEditedRow(draftRow);
    setIsPriceDialogOpen(true);
  };

  const submitPrice = (row: ModelPriceRow) => {
    if (editedRow) {
      dispatch({ type: "edited", key: editedRow.key, changes: row });
    } else {
      dispatch({ type: "added", row });
    }
  };

  const openReview = () => {
    dialogKeyRef.current += 1;
    setIsReviewOpen(true);
  };

  if (isPending) {
    return <Loader />;
  }

  if (isError) {
    return (
      <p className="comet-body-s py-8 text-center text-muted-slate">
        The prices couldn&apos;t be loaded. Refresh the page to try again.
      </p>
    );
  }

  return (
    <div data-testid="cost-center-tabpanel" className="pb-24">
      <CostCenterHeader
        version={draft.version}
        organizationName={access.organizationName}
        canEdit={canEdit}
        isAccessPending={access.isPending}
        latest={history[0]}
        history={history}
        isHistoryOpen={isHistoryOpen}
        onToggleHistory={() => setIsHistoryOpen((open) => !open)}
      />

      <div className="mb-4 flex w-full items-center justify-between gap-3">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          className="w-[320px]"
          placeholder="Search models"
          dimension="sm"
        />
        {canEdit && (
          <Button size="sm" onClick={() => openPriceDialog()}>
            <Plus className="mr-1.5 size-3.5" />
            Add price
          </Button>
        )}
      </div>

      {draft.listErrors.length > 0 && (
        <ul className="comet-body-s mb-4 rounded-md border border-destructive/40 p-3 text-destructive">
          {draft.listErrors.map((problem, index) => (
            <li key={index}>{problem.message}</li>
          ))}
        </ul>
      )}

      {draft.rows.length === 0 ? (
        <div className="rounded-md border py-10">
          <DataTableEmptyContent
            title="No organization prices yet"
            description="Spans are priced with the built-in list. Add a price to replace it for a model, for example a contract rate or a self-hosted model."
          >
            {canEdit && (
              <Button size="sm" onClick={() => openPriceDialog()}>
                Add price
              </Button>
            )}
          </DataTableEmptyContent>
        </div>
      ) : visibleRows.length === 0 ? (
        <p className="comet-body-s py-8 text-center text-muted-slate">
          No prices match &quot;{search}&quot;.
        </p>
      ) : (
        <ModelPricesTable
          rows={visibleRows}
          canEdit={canEdit}
          errors={errors}
          warnings={draft.warnings}
          changedElsewhere={draft.changedElsewhere}
          dispatch={dispatch}
          onEdit={openPriceDialog}
        />
      )}

      {canEdit && isDirty && (
        <div
          data-testid="model-prices-save-bar"
          className="sticky bottom-4 z-10 mt-4 flex items-center justify-between gap-3 rounded-md border bg-background p-3 shadow-md"
        >
          <span className="comet-body-s">
            Unsaved changes: {describeCounts(counts)}.
            {hasClientErrors && (
              <span className="text-destructive">
                {" "}
                Fix the rows marked in red first.
              </span>
            )}
          </span>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="outline"
              onClick={() => dispatch({ type: "discarded" })}
            >
              Discard
            </Button>
            <Button size="sm" onClick={openReview} disabled={hasClientErrors}>
              Review and save
            </Button>
          </div>
        </div>
      )}

      {isPriceDialogOpen && (
        <ModelPriceDialog
          key={`price-${dialogKeyRef.current}`}
          open={isPriceDialogOpen}
          setOpen={setIsPriceDialogOpen}
          row={editedRow?.row}
          takenIdentities={takenIdentities}
          onSubmit={submitPrice}
        />
      )}

      {isReviewOpen && (
        <ReviewModelPricesDialog
          key={`review-${dialogKeyRef.current}`}
          open={isReviewOpen}
          setOpen={setIsReviewOpen}
          preview={preview}
          save={save}
        />
      )}
    </div>
  );
};

export default CostCenterTab;
