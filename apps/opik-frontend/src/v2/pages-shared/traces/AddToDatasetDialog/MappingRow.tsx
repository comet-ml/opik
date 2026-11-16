import React from "react";
import { MessageCircleWarning, Pencil, X } from "lucide-react";

import { Input } from "@/ui/input";
import { Tag } from "@/ui/tag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import FieldRowShell from "@/v2/components/FieldRowShell/FieldRowShell";
import { JsonObject } from "@/types/shared";
import { cn } from "@/lib/utils";
import PathSourcePicker from "./PathSourcePicker";
import { MAX_FIELD_NAME_LENGTH } from "@/constants/datasets";
import { MappingRow as MappingRowType } from "./fieldMappingTypes";
import { FieldRowError } from "./useFieldMappings";
import { FieldCoverage } from "./useFieldCoverage";

const ROW_ERROR_TEXT: Record<FieldRowError, string> = {
  blank: "Field name is required",
  duplicate: "Field name is already used",
  too_long: `Field name must be ${MAX_FIELD_NAME_LENGTH} characters or fewer`,
  no_path: "Select a field to map",
};

type MappingRowProps = {
  row: MappingRowType;
  treeData: JsonObject;
  coverage?: FieldCoverage;
  rowError?: FieldRowError;
  newColumnItemCount?: number;
  isPending?: boolean;
  entityLabel?: string;
  autoFocusName?: boolean;
  onRename?: (name: string) => void;
  onPathSelect?: (path: string) => void;
  onPathClear?: () => void;
  onRemove?: () => void;
  removeDisabledReason?: string;
};

const MappingRow: React.FunctionComponent<MappingRowProps> = ({
  row,
  treeData,
  coverage,
  rowError,
  newColumnItemCount,
  isPending,
  entityLabel,
  autoFocusName,
  onRename,
  onPathSelect,
  onPathClear,
  onRemove,
  removeDisabledReason,
}) => {
  const isManaged = row.kind === "managed";
  const isCustom = row.kind === "custom";

  const renderCoverage = () => {
    if (!coverage || coverage.covered >= coverage.total) return null;

    return (
      <TooltipWrapper
        content={`${coverage.covered} of ${coverage.total} selected ${
          coverage.total === 1 ? "item has" : "items have"
        } a value for this field`}
      >
        <Tag variant="orange" size="sm" className="shrink-0 text-xs">
          {coverage.covered}/{coverage.total}
        </Tag>
      </TooltipWrapper>
    );
  };

  const renderName = () => {
    if (!isCustom) {
      return (
        <div className="flex h-6 items-center rounded-md border border-border bg-background px-2">
          <span className="comet-body-xs truncate">{row.name}</span>
        </div>
      );
    }

    return (
      <div className="relative">
        <Input
          dimension="xs"
          value={row.name}
          placeholder="Field name"
          className={cn(
            "pl-2 pr-6",
            rowError && rowError !== "no_path" && "border-destructive",
          )}
          autoFocus={autoFocusName}
          onFocus={(event) => autoFocusName && event.currentTarget.select()}
          onChange={(event) => onRename?.(event.target.value)}
        />
        <Pencil className="pointer-events-none absolute right-2 top-1/2 size-3 -translate-y-1/2 text-light-slate" />
      </div>
    );
  };

  const renderSource = () => {
    if (isManaged) {
      return (
        <div className="flex h-6 items-center gap-1 rounded-md border border-border bg-background px-2">
          <span className="comet-code min-w-0 flex-1 truncate text-xs text-foreground">
            {row.name}
          </span>
          {renderCoverage()}
        </div>
      );
    }

    return (
      <PathSourcePicker
        fieldName={row.name}
        path={row.path}
        placeholder={onPathClear ? "Not mapped" : undefined}
        badge={renderCoverage()}
        invalid={rowError === "no_path"}
        treeData={treeData}
        isPending={isPending}
        entityLabel={entityLabel}
        onSelect={(path) => onPathSelect?.(path)}
        onClear={onPathClear}
      />
    );
  };

  return (
    <FieldRowShell
      data-testid={`mapping-row-${row.id}`}
      name={renderName()}
      value={renderSource()}
      removeLabel={`Remove ${row.name || "field"}`}
      removeIcon={<X />}
      removeVariant="inline"
      removeDisabledReason={removeDisabledReason}
      onRemove={onRemove}
      note={
        !rowError &&
        Boolean(newColumnItemCount) && (
          <div className="flex items-center gap-1 rounded-md bg-muted p-2">
            <MessageCircleWarning className="size-3 shrink-0 text-muted-slate" />
            <span className="comet-body-xs text-muted-slate">
              New field — the {newColumnItemCount} items already in this dataset
              will display this field as empty.
            </span>
          </div>
        )
      }
      message={
        rowError && (
          <p className="comet-body-xs mt-1 text-destructive">
            {ROW_ERROR_TEXT[rowError]}
          </p>
        )
      }
    />
  );
};

export default MappingRow;
