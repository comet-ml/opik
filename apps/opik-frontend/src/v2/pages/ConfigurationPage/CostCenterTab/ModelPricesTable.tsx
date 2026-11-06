import React, { useMemo } from "react";
import { MoreHorizontal, Pencil, RotateCcw, Trash } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Tag } from "@/ui/tag";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/ui/table";
import {
  DraftRow,
  DraftRowStatus,
  ModelPriceProblem,
  ModelPriceRow,
  ModelPricesDraftAction,
  PRICE_FIELDS,
  PRICE_LABELS,
  PriceField,
  describeAliasTarget,
  statusOf,
} from "@/lib/model-prices";

const FIELD_LABELS: Record<string, string> = {
  model: "Model",
  provider: "Provider",
  kind: "Type",
  note: "Note",
  "alias_of.model": "Priced like",
  "alias_of.provider": "Priced like provider",
  ...PRICE_LABELS,
};

const STATUS_TAGS: Partial<
  Record<DraftRowStatus, { label: string; variant: "green" | "blue" | "red" }>
> = {
  new: { label: "New", variant: "green" },
  edited: { label: "Edited", variant: "blue" },
  deleted: { label: "Removed", variant: "red" },
};

const COLUMN_COUNT = 3 + PRICE_FIELDS.length + 2;

type ModelPricesTableProps = {
  rows: DraftRow[];
  canEdit: boolean;
  errors: Record<string, ModelPriceProblem[]>;
  warnings: Record<string, ModelPriceProblem[]>;
  changedElsewhere: string[];
  dispatch: React.Dispatch<ModelPricesDraftAction>;
  onEdit: (draftRow: DraftRow) => void;
};

const hasFieldProblem = (problems: ModelPriceProblem[], field: string) =>
  problems.some((problem) => problem.field === field);

const describeProblem = (problem: ModelPriceProblem) => {
  const label = problem.field ? FIELD_LABELS[problem.field] : undefined;
  return label ? `${label}: ${problem.message}` : problem.message;
};

type PriceCellProps = {
  draftRow: DraftRow;
  field: PriceField;
  canEdit: boolean;
  invalid: boolean;
  dispatch: React.Dispatch<ModelPricesDraftAction>;
};

const PriceCell: React.FC<PriceCellProps> = ({
  draftRow,
  field,
  canEdit,
  invalid,
  dispatch,
}) => {
  const value = draftRow.row[field] ?? "";

  if (!canEdit || draftRow.deleted) {
    return (
      <span
        className={cn(
          "comet-code block text-right",
          !value && "text-light-slate",
        )}
      >
        {value || "built-in"}
      </span>
    );
  }

  return (
    <Input
      dimension="sm"
      inputMode="decimal"
      aria-label={`${PRICE_LABELS[field]} price for ${draftRow.row.model}`}
      aria-invalid={invalid}
      className={cn(
        "comet-code text-right",
        invalid && "border-destructive focus-visible:border-destructive",
      )}
      placeholder="built-in"
      value={value}
      onChange={(event) =>
        dispatch({
          type: "edited",
          key: draftRow.key,
          changes: { [field]: event.target.value } as Partial<ModelPriceRow>,
        })
      }
    />
  );
};

type RowActionsProps = {
  draftRow: DraftRow;
  status: DraftRowStatus;
  dispatch: React.Dispatch<ModelPricesDraftAction>;
  onEdit: (draftRow: DraftRow) => void;
};

const RowActions: React.FC<RowActionsProps> = ({
  draftRow,
  status,
  dispatch,
  onEdit,
}) => {
  if (status === "deleted") {
    return (
      <Button
        variant="ghost"
        size="2xs"
        onClick={() => dispatch({ type: "restored", key: draftRow.key })}
      >
        Restore
      </Button>
    );
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="minimal" size="icon-sm">
          <span className="sr-only">Actions for {draftRow.row.model}</span>
          <MoreHorizontal className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        <DropdownMenuItem onClick={() => onEdit(draftRow)}>
          <Pencil className="mr-2 size-4" />
          Edit
        </DropdownMenuItem>
        {status === "edited" && (
          <DropdownMenuItem
            onClick={() => dispatch({ type: "restored", key: draftRow.key })}
          >
            <RotateCcw className="mr-2 size-4" />
            Undo changes
          </DropdownMenuItem>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          variant="destructive"
          onClick={() => dispatch({ type: "removed", key: draftRow.key })}
        >
          <Trash className="mr-2 size-4" />
          Remove
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

const ModelPricesTable: React.FC<ModelPricesTableProps> = ({
  rows,
  canEdit,
  errors,
  warnings,
  changedElsewhere,
  dispatch,
  onEdit,
}) => {
  const changedElsewhereKeys = useMemo(
    () => new Set(changedElsewhere),
    [changedElsewhere],
  );

  return (
    <div className="overflow-x-auto rounded-md border">
      <Table className="min-w-[1100px]">
        <TableHeader>
          <TableRow>
            <TableHead className="px-3 py-2">Model</TableHead>
            <TableHead className="w-36 px-3 py-2">Provider</TableHead>
            <TableHead className="w-20 px-3 py-2">Type</TableHead>
            {PRICE_FIELDS.map((field) => (
              <TableHead key={field} className="w-28 px-3 py-2 text-right">
                {PRICE_LABELS[field]} / 1M
              </TableHead>
            ))}
            <TableHead className="w-60 px-3 py-2">Note</TableHead>
            <TableHead className="w-12 px-3 py-2">
              <span className="sr-only">Actions</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((draftRow) => {
            const status = statusOf(draftRow);
            const statusTag = STATUS_TAGS[status];
            const rowErrors = errors[draftRow.key] ?? [];
            const rowWarnings = warnings[draftRow.key] ?? [];
            const isChangedElsewhere = changedElsewhereKeys.has(draftRow.key);
            const { row } = draftRow;
            const isEditable = canEdit && !draftRow.deleted;
            const hasProblems = rowErrors.length > 0 || rowWarnings.length > 0;

            return (
              <React.Fragment key={draftRow.key}>
                <TableRow
                  data-testid="model-price-row"
                  className={cn(
                    draftRow.deleted && "text-light-slate",
                    hasProblems && "border-b-0",
                  )}
                >
                  <TableCell className="px-3 py-2 align-top">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span
                        className={cn(
                          "comet-code break-all",
                          draftRow.deleted && "line-through",
                        )}
                      >
                        {row.model}
                      </span>
                      {statusTag && (
                        <Tag size="sm" variant={statusTag.variant}>
                          {statusTag.label}
                        </Tag>
                      )}
                      {isChangedElsewhere && (
                        <Tag size="sm" variant="orange">
                          Also changed by someone else
                        </Tag>
                      )}
                    </div>
                  </TableCell>
                  <TableCell
                    className={cn(
                      "px-3 py-2 align-top",
                      !row.provider?.trim() && "text-light-slate",
                    )}
                  >
                    {row.provider?.trim() ? row.provider : "Any provider"}
                  </TableCell>
                  <TableCell className="px-3 py-2 align-top">
                    <Tag size="sm" variant="gray">
                      {row.kind === "alias" ? "Alias" : "Fixed"}
                    </Tag>
                  </TableCell>
                  {row.kind === "alias" ? (
                    <TableCell
                      colSpan={PRICE_FIELDS.length}
                      className="px-3 py-2 align-top"
                    >
                      Priced like{" "}
                      <span className="comet-code">
                        {describeAliasTarget(row)}
                      </span>
                    </TableCell>
                  ) : (
                    PRICE_FIELDS.map((field) => (
                      <TableCell key={field} className="px-2 py-1.5 align-top">
                        <PriceCell
                          draftRow={draftRow}
                          field={field}
                          canEdit={canEdit}
                          invalid={hasFieldProblem(rowErrors, field)}
                          dispatch={dispatch}
                        />
                      </TableCell>
                    ))
                  )}
                  <TableCell className="px-2 py-1.5 align-top">
                    {isEditable ? (
                      <Input
                        dimension="sm"
                        aria-label={`Note for ${row.model}`}
                        aria-invalid={hasFieldProblem(rowErrors, "note")}
                        placeholder="Add a note"
                        value={row.note ?? ""}
                        onChange={(event) =>
                          dispatch({
                            type: "edited",
                            key: draftRow.key,
                            changes: { note: event.target.value },
                          })
                        }
                      />
                    ) : (
                      <span className="block px-1 py-1.5">{row.note}</span>
                    )}
                  </TableCell>
                  <TableCell className="px-2 py-1.5 text-right align-top">
                    {canEdit && (
                      <RowActions
                        draftRow={draftRow}
                        status={status}
                        dispatch={dispatch}
                        onEdit={onEdit}
                      />
                    )}
                  </TableCell>
                </TableRow>
                {hasProblems && (
                  <TableRow>
                    <TableCell
                      colSpan={COLUMN_COUNT}
                      className="px-3 pb-2 pt-0"
                    >
                      <ul className="comet-body-xs flex flex-col gap-0.5">
                        {rowErrors.map((problem, index) => (
                          <li key={`e-${index}`} className="text-destructive">
                            {describeProblem(problem)}
                          </li>
                        ))}
                        {rowWarnings.map((problem, index) => (
                          <li
                            key={`w-${index}`}
                            className="text-warning-box-text"
                          >
                            {describeProblem(problem)}
                          </li>
                        ))}
                      </ul>
                    </TableCell>
                  </TableRow>
                )}
              </React.Fragment>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
};

export default ModelPricesTable;
