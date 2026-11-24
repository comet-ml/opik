import React, { useMemo, useRef, useState } from "react";
import { Info, Lock, Plus, RotateCcw, Star, Trash2, X } from "lucide-react";
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetDescription,
  SheetTitle,
} from "@/ui/sheet";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Tag } from "@/ui/tag";
import { Textarea } from "@/ui/textarea";
import SelectBox from "@/shared/SelectBox/SelectBox";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import {
  BuiltInPrice,
  CATALOG_TYPE_LABELS,
  CatalogEntry,
  CatalogType,
  DrawerValues,
  LITELLM_KEYS,
  ModelPriceList,
  ModelPriceProblem,
  ModelPriceRow,
  PRICE_FIELDS,
  PRICE_LABELS,
  PriceField,
  blockingWarnings,
  canonicalPrice,
  describePerToken,
  drawerValuesOf,
  findBuiltIn,
  identityOf,
  isSameSavedRow,
  overrideFields,
  problemsForRow,
  rebaseValues,
  replaceOne,
  validateRow,
} from "@/lib/model-prices";
import {
  MAX_QUICK_ACCESS,
  MODE_LABELS,
  PRICE_UNIT_SUFFIX,
  TYPE_TAG_VARIANT,
  formatDay,
} from "./format";
import useSaveModelPrices from "./useSaveModelPrices";

export type DrawerTarget = {
  entry?: CatalogEntry;
};

type Line = { id: number; field: PriceField; value: string; locked: boolean };

const KeyName: React.FC<{ name: string }> = ({ name }) => (
  <>
    {name.split("_").map((part, index) => (
      <React.Fragment key={index}>
        {index > 0 && (
          <>
            _<wbr />
          </>
        )}
        {part}
      </React.Fragment>
    ))}
  </>
);

const READ_ONLY_INPUT = "bg-muted hover:shadow-none";

type ModelPriceDrawerProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  target: DrawerTarget;
  list: ModelPriceList;
  builtIns: BuiltInPrice[];
  canEdit: boolean;
  isPinned?: boolean;
  canPin?: boolean;
  onTogglePin?: () => void;
};

const initialLines = (entry?: CatalogEntry): Line[] => {
  if (!entry) {
    return [
      { id: 1, field: "input_price", value: "", locked: false },
      { id: 2, field: "output_price", value: "", locked: false },
    ];
  }
  return PRICE_FIELDS.filter((field) => entry.prices[field]).map(
    (field, index) => ({
      id: index + 1,
      field,
      value: entry.prices[field] ?? "",
      locked: true,
    }),
  );
};

const rebaseLines = (
  lines: Line[],
  prices: DrawerValues["prices"],
  newId: () => number,
): Line[] => {
  const kept = lines.flatMap((line) => {
    const value = prices[line.field];
    if (value !== undefined) return [{ ...line, value }];
    return line.locked ? [] : [{ ...line, value: "" }];
  });
  const shown = new Set(kept.map((line) => line.field));
  const added = PRICE_FIELDS.filter(
    (field) => prices[field] !== undefined && !shown.has(field),
  ).map((field) => ({
    id: newId(),
    field,
    value: prices[field] ?? "",
    locked: true,
  }));
  return [...kept, ...added];
};

type Loaded = { row?: ModelPriceRow; values: DrawerValues };

type ChangedElsewhere = { version: number; removed: boolean };

const typeOf = (
  loadedRow: ModelPriceRow | undefined,
  builtIn: BuiltInPrice | undefined,
): CatalogType => {
  if (loadedRow?.kind === "alias" || !builtIn) return "custom";
  return loadedRow ? "override" : "default";
};

const ModelPriceDrawer: React.FC<ModelPriceDrawerProps> = ({
  open,
  setOpen,
  target,
  list,
  builtIns,
  canEdit,
  isPinned = false,
  canPin = true,
  onTogglePin,
}) => {
  const { entry } = target;
  const isNew = !entry;
  const { send, isSaving } = useSaveModelPrices();
  const nextId = useRef(10);

  const [model, setModel] = useState(entry?.model ?? "");
  const [provider, setProvider] = useState(entry?.provider ?? "");
  const [kind, setKind] = useState<ModelPriceRow["kind"]>(
    entry?.row?.kind ?? "fixed",
  );
  const [aliasModel, setAliasModel] = useState(
    entry?.row?.alias_of?.model ?? "",
  );
  const [aliasProvider, setAliasProvider] = useState(
    entry?.row?.alias_of?.provider ?? "",
  );
  const [lines, setLines] = useState<Line[]>(() => initialLines(entry));
  const [note, setNote] = useState(entry?.row?.note ?? "");
  const [problems, setProblems] = useState<ModelPriceProblem[]>([]);
  const [warnings, setWarnings] = useState<ModelPriceProblem[]>([]);
  const [isDirty, setIsDirty] = useState(false);
  const [confirming, setConfirming] = useState<"discard" | "remove">();
  // Looked up by model and provider, not taken from the entry: an alias entry has no built-in, but the row can
  // become a fixed price (an override of that built-in) while the drawer is open.
  const [identityBuiltIn] = useState(() =>
    entry ? findBuiltIn(builtIns, entry.model, entry.provider) : undefined,
  );
  const [loaded, setLoaded] = useState<Loaded>(() => ({
    row: entry?.row,
    values: drawerValuesOf(entry?.row, identityBuiltIn),
  }));
  const [changedElsewhere, setChangedElsewhere] = useState<ChangedElsewhere>();

  const builtIn = useMemo(
    () =>
      entry?.builtIn ??
      (kind === "fixed" ? findBuiltIn(builtIns, model, provider) : undefined),
    [entry, builtIns, model, provider, kind],
  );
  const type = entry
    ? typeOf(loaded.row, identityBuiltIn)
    : builtIn
      ? "override"
      : "custom";
  const keepsBuiltIn = Boolean(builtIn);
  const isCustomPrice = !keepsBuiltIn && kind === "fixed";
  const readOnly = !canEdit;

  const values = useMemo(() => {
    const result: Partial<Record<PriceField, string>> = {};
    lines.forEach((line) => {
      result[line.field] = line.value;
    });
    return result;
  }, [lines]);

  const identity = entry
    ? identityOf(
        entry.row ?? {
          model: entry.model,
          provider: entry.provider,
          kind: "fixed",
        },
      )
    : undefined;
  const latestRow = identity
    ? list.rows.find((row) => identityOf(row) === identity)
    : undefined;
  // The list refetches in the background (window focus, after a 409), so someone else's save can land while
  // the drawer is open. Rebase in render, not in an effect, so the stale values are never shown or saved.
  if (identity && !isSameSavedRow(latestRow, loaded.row)) {
    const theirs = drawerValuesOf(latestRow, identityBuiltIn);
    const merged = rebaseValues(
      loaded.values,
      { kind, aliasModel, aliasProvider, note, prices: values },
      theirs,
    );
    setKind(merged.kind);
    setAliasModel(merged.aliasModel);
    setAliasProvider(merged.aliasProvider);
    setNote(merged.note);
    setLines((current) =>
      rebaseLines(current, merged.prices, () => (nextId.current += 1)),
    );
    setLoaded({ row: latestRow, values: theirs });
    setChangedElsewhere({ version: list.version, removed: !latestRow });
    setProblems([]);
    setWarnings([]);
  }

  const buildRow = (): ModelPriceRow => {
    const base: ModelPriceRow = {
      model: model.trim(),
      provider: provider.trim(),
      kind,
      note: note.trim() || undefined,
    };
    if (kind === "alias") {
      return {
        ...base,
        alias_of: { model: aliasModel.trim(), provider: aliasProvider.trim() },
      };
    }
    if (keepsBuiltIn) {
      return { ...base, ...overrideFields(builtIn, values) };
    }
    const prices: Partial<Record<PriceField, string>> = {};
    lines.forEach((line) => {
      const value = canonicalPrice(line.value);
      if (value) {
        prices[line.field] = value;
      }
    });
    return { ...base, ...prices };
  };

  const resetFeedback = () => {
    setProblems([]);
    setWarnings([]);
    setIsDirty(true);
  };

  const requestClose = () => {
    if (isDirty && canEdit) {
      setConfirming("discard");
      return;
    }
    setOpen(false);
  };

  const updateLine = (id: number, changes: Partial<Line>) => {
    resetFeedback();
    setLines((current) =>
      current.map((line) => (line.id === id ? { ...line, ...changes } : line)),
    );
  };

  const usedFields = new Set(lines.map((line) => line.field));
  const freeFields = PRICE_FIELDS.filter((field) => !usedFields.has(field));

  const addLine = () => {
    if (!freeFields.length) return;
    resetFeedback();
    nextId.current += 1;
    setLines((current) => [
      ...current,
      { id: nextId.current, field: freeFields[0], value: "", locked: false },
    ]);
  };

  const removeLine = (id: number) => {
    resetFeedback();
    setLines((current) => current.filter((line) => line.id !== id));
  };

  const duplicate =
    isNew &&
    list.rows.some(
      (row) =>
        identityOf(row) === identityOf({ model, provider, kind: "fixed" }),
    );

  const save = async (remove: boolean) => {
    const next = remove ? undefined : buildRow();
    const hasOverride =
      next && keepsBuiltIn && kind === "fixed"
        ? PRICE_FIELDS.some((field) => next[field])
        : true;
    const rowToSave = next && !hasOverride && !next.note ? undefined : next;
    if (rowToSave) {
      const found = validateRow(rowToSave).filter(
        (problem) => !(keepsBuiltIn && problem.code === "no_price"),
      );
      if (duplicate) {
        found.push({
          row: null,
          field: "model",
          code: "duplicate",
          message:
            "This model already has an organization price. Open it from the table to change it.",
        });
      }
      if (found.length) {
        setProblems(found);
        return;
      }
    }
    if (!rowToSave && !loaded.row) {
      setOpen(false);
      return;
    }
    const { request, index } = replaceOne(list, loaded.row, rowToSave);
    if (!warnings.length) {
      const preview = await send(request, true);
      if (preview.kind === "invalid") {
        setProblems(problemsForRow(preview.errors, index));
        return;
      }
      if (preview.kind !== "previewed") {
        return;
      }
      const found =
        index >= 0
          ? blockingWarnings(
              problemsForRow(preview.result.warnings, index),
              isCustomPrice,
            )
          : [];
      if (found.length) {
        setWarnings(found);
        return;
      }
    }
    const outcome = await send(request, false);
    if (outcome.kind === "saved") {
      setOpen(false);
    } else if (outcome.kind === "invalid") {
      setProblems(problemsForRow(outcome.errors, index));
    } else {
      setWarnings([]);
    }
  };

  const fieldProblems = (field: string) =>
    problems.filter((problem) => problem.field === field);
  const isShownByItsField = (field?: string | null) => {
    if (!field) return false;
    if (field === "note") return true;
    if (field === "model") return isNew;
    if (field.startsWith("alias_of")) return kind === "alias";
    return kind === "fixed" && usedFields.has(field as PriceField);
  };
  const priceProblems = problems.filter(
    (problem) => problem.code === "no_price",
  );
  const generalProblems = problems.filter(
    (problem) =>
      problem.code !== "no_price" && !isShownByItsField(problem.field),
  );
  const cachePriced = lines.some(
    (line) =>
      line.field === "cache_read_price" && canonicalPrice(line.value) !== "",
  );

  const title = entry ? entry.model : "New custom model";
  const helpText = keepsBuiltIn
    ? "Per-token prices are shown per 1M tokens for readability. Empty = built-in price."
    : "Per-token prices are shown per 1M tokens for readability. Empty = no price (not $0).";
  const providerLabel = provider.trim() || "Any provider";

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => (next ? setOpen(true) : requestClose())}
    >
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[560px]"
        header={null}
        blockOverlayClose
      >
        <div className="flex shrink-0 flex-col gap-2 px-6 pt-6">
          <div className="flex items-start justify-between gap-3">
            <SheetTitle className="comet-title-m break-all text-left font-mono">
              {title}
            </SheetTitle>
            <div className="flex shrink-0 items-center gap-1.5">
              {onTogglePin && (
                <TooltipWrapper
                  hoverOnly
                  content={
                    isPinned
                      ? "Unpin from quick access"
                      : canPin
                        ? "Pin to quick access"
                        : `Quick access holds ${MAX_QUICK_ACCESS} models`
                  }
                >
                  <Button
                    variant="outline"
                    size="icon-2xs"
                    className={cn(
                      isPinned && "text-warning-box-text",
                      !isPinned && !canPin && "cursor-not-allowed opacity-50",
                    )}
                    aria-label={
                      isPinned
                        ? "Unpin from quick access"
                        : "Pin to quick access"
                    }
                    aria-pressed={isPinned}
                    aria-disabled={!isPinned && !canPin}
                    onClick={() => (isPinned || canPin) && onTogglePin()}
                  >
                    <Star className={cn(isPinned && "fill-current")} />
                  </Button>
                </TooltipWrapper>
              )}
              <SheetClose asChild>
                <Button variant="outline" size="icon-2xs">
                  <X />
                  <span className="sr-only">Close</span>
                </Button>
              </SheetClose>
            </div>
          </div>
          <SheetDescription className="sr-only">
            {CATALOG_TYPE_LABELS[type]} price for {providerLabel}
          </SheetDescription>
          <div className="flex flex-wrap gap-1.5">
            <Tag variant={TYPE_TAG_VARIANT[type]}>
              {CATALOG_TYPE_LABELS[type]}
            </Tag>
            <Tag variant="default">{providerLabel}</Tag>
            {entry?.mode && (
              <Tag variant="gray">{MODE_LABELS[entry.mode] ?? entry.mode}</Tag>
            )}
            {entry?.lastUpdatedAt && (
              <Tag variant="default">
                Updated {formatDay(entry.lastUpdatedAt)}
              </Tag>
            )}
          </div>
        </div>
        <div className="min-h-0 flex-1 space-y-5 overflow-y-auto px-6 pb-4 pt-2">
          {changedElsewhere && (
            <div
              role="status"
              className="comet-body-s rounded-md border border-warning-box-text/40 bg-warning-box-bg p-3"
              data-testid="model-price-changed-elsewhere"
            >
              <p>
                {changedElsewhere.removed
                  ? `Someone else removed this model's organization price (version ${changedElsewhere.version}). Saving adds it back with the values below.`
                  : `Someone else saved version ${changedElsewhere.version} while you were editing. Their prices are loaded below and your changes are kept on top. Review them before saving.`}
              </p>
            </div>
          )}
          {isNew && (
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="model-price-model">Model name</Label>
                <Input
                  id="model-price-model"
                  dimension="sm"
                  className="font-mono"
                  placeholder="e.g. acme-finetune-v3"
                  value={model}
                  onChange={(event) => {
                    resetFeedback();
                    setModel(event.target.value);
                  }}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="model-price-provider">Provider</Label>
                <Input
                  id="model-price-provider"
                  dimension="sm"
                  placeholder="Any provider"
                  value={provider}
                  onChange={(event) => {
                    resetFeedback();
                    setProvider(event.target.value);
                  }}
                />
              </div>
              {fieldProblems("model").map((problem, i) => (
                <p
                  key={i}
                  className="comet-body-xs col-span-2 text-destructive"
                >
                  {problem.message}
                </p>
              ))}
              {builtIn && (
                <p className="comet-body-xs col-span-2 text-muted-slate">
                  This model has a built-in price. Saving creates an override
                  for the fields you change.
                </p>
              )}
            </div>
          )}

          {canEdit && (isNew || type === "custom") && (
            <div className="flex gap-1 rounded-md border p-1" role="group">
              {(["fixed", "alias"] as const).map((option) => (
                <Button
                  key={option}
                  size="xs"
                  variant={kind === option ? "secondary" : "ghost"}
                  onClick={() => {
                    resetFeedback();
                    setKind(option);
                  }}
                >
                  {option === "fixed" ? "Own price" : "Same as another model"}
                </Button>
              ))}
            </div>
          )}

          {kind === "alias" ? (
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="model-price-alias-model">Priced like</Label>
                <Input
                  id="model-price-alias-model"
                  dimension="sm"
                  className={cn("font-mono", readOnly && READ_ONLY_INPUT)}
                  placeholder="e.g. gpt-4o"
                  value={aliasModel}
                  readOnly={readOnly}
                  onChange={(event) => {
                    resetFeedback();
                    setAliasModel(event.target.value);
                  }}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="model-price-alias-provider">On provider</Label>
                <Input
                  id="model-price-alias-provider"
                  dimension="sm"
                  className={cn(readOnly && READ_ONLY_INPUT)}
                  placeholder="The span's own provider"
                  value={aliasProvider}
                  readOnly={readOnly}
                  onChange={(event) => {
                    resetFeedback();
                    setAliasProvider(event.target.value);
                  }}
                />
              </div>
              {problems
                .filter((problem) => problem.field?.startsWith("alias_of"))
                .map((problem, i) => (
                  <p
                    key={i}
                    className="comet-body-xs col-span-2 text-destructive"
                  >
                    {problem.message}
                  </p>
                ))}
            </div>
          ) : (
            <div className="space-y-3">
              <div>
                <h4 className="comet-body-s-accented">Prices</h4>
                <p className="comet-body-xs text-muted-slate">{helpText}</p>
                {canEdit && isCustomPrice && !cachePriced && (
                  <p className="comet-body-xs mt-1 text-light-slate">
                    Cached tokens are billed at the input price unless you add a
                    cache price.
                  </p>
                )}
              </div>
              {lines.map((line) => {
                const builtInValue = builtIn?.[line.field];
                const canReset =
                  type === "override" &&
                  canEdit &&
                  builtInValue &&
                  canonicalPrice(line.value) !== builtInValue;
                return (
                  <div key={line.id} className="space-y-1">
                    <div className="flex items-center gap-2">
                      {line.locked || readOnly ? (
                        <div className="comet-body-s flex h-8 flex-1 items-center gap-2 rounded-md bg-muted px-3">
                          {line.locked && (
                            <Lock className="size-3.5 text-light-slate" />
                          )}
                          {PRICE_LABELS[line.field]}
                        </div>
                      ) : (
                        <SelectBox
                          className="h-8 flex-1"
                          value={line.field}
                          onChange={(field: PriceField) =>
                            updateLine(line.id, { field })
                          }
                          options={PRICE_FIELDS.filter(
                            (field) =>
                              field === line.field || !usedFields.has(field),
                          ).map((field) => ({
                            value: field,
                            label: PRICE_LABELS[field],
                          }))}
                        />
                      )}
                      <div className="relative flex-1">
                        <span className="comet-body-s absolute left-3 top-1/2 -translate-y-1/2 text-foreground">
                          $
                        </span>
                        <Input
                          dimension="sm"
                          className={cn(
                            "pl-6 pr-16 font-mono",
                            readOnly && READ_ONLY_INPUT,
                          )}
                          aria-label={`${
                            PRICE_LABELS[line.field]
                          } price per 1M tokens`}
                          placeholder={builtInValue ?? "No price"}
                          value={line.value}
                          readOnly={readOnly}
                          onChange={(event) =>
                            updateLine(line.id, { value: event.target.value })
                          }
                        />
                        <span className="comet-body-xs absolute right-3 top-1/2 -translate-y-1/2 font-mono text-light-slate">
                          {PRICE_UNIT_SUFFIX}
                        </span>
                      </div>
                      {canReset ? (
                        <TooltipWrapper content="Reset to built-in price">
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            aria-label={`Reset ${
                              PRICE_LABELS[line.field]
                            } to the built-in price`}
                            onClick={() =>
                              updateLine(line.id, {
                                value: builtInValue ?? "",
                              })
                            }
                          >
                            <RotateCcw className="size-3.5" />
                          </Button>
                        </TooltipWrapper>
                      ) : !line.locked && canEdit ? (
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Remove ${PRICE_LABELS[line.field]}`}
                          onClick={() => removeLine(line.id)}
                        >
                          <Trash2 className="size-3.5" />
                        </Button>
                      ) : (
                        <span className="w-8" />
                      )}
                    </div>
                    <div className="comet-body-xs flex gap-2 font-mono text-light-slate">
                      <span className="flex-1 break-words pl-3">
                        <KeyName name={LITELLM_KEYS[line.field]} />
                      </span>
                      <span className="flex-1 truncate pl-3">
                        {describePerToken(
                          line.value,
                          keepsBuiltIn ? builtInValue : undefined,
                        )}
                      </span>
                      <span className="w-8 shrink-0" />
                    </div>
                    {fieldProblems(line.field).map((problem, i) => (
                      <p
                        key={i}
                        className="comet-body-xs pl-3 text-destructive"
                      >
                        {problem.message}
                      </p>
                    ))}
                  </div>
                );
              })}
              {priceProblems.map((problem, i) => (
                <p key={i} className="comet-body-xs pl-3 text-destructive">
                  {problem.message}
                </p>
              ))}
              {canEdit && freeFields.length > 0 && (
                <Button variant="outline" size="sm" onClick={addLine}>
                  <Plus className="mr-1.5 size-3.5" />
                  Add price
                </Button>
              )}
            </div>
          )}

          {readOnly ? (
            note && (
              <div className="space-y-1.5">
                <h4 className="comet-body-s-accented">Note</h4>
                <p className="comet-body-s whitespace-pre-wrap break-words">
                  {note}
                </p>
              </div>
            )
          ) : (
            <div className="space-y-1.5">
              <Label htmlFor="model-price-note">Note</Label>
              <Textarea
                id="model-price-note"
                rows={3}
                className="min-h-[76px]"
                placeholder="Why was this changed? e.g. negotiated rate, contract #…"
                value={note}
                onChange={(event) => {
                  resetFeedback();
                  setNote(event.target.value);
                }}
              />
              {fieldProblems("note").map((problem, i) => (
                <p key={i} className="comet-body-xs text-destructive">
                  {problem.message}
                </p>
              ))}
            </div>
          )}

          {generalProblems.length > 0 && (
            <ul className="comet-body-s space-y-1 rounded-md border border-destructive/40 p-3 text-destructive">
              {generalProblems.map((problem, i) => (
                <li key={i}>{problem.message}</li>
              ))}
            </ul>
          )}

          {warnings.length > 0 && (
            <div
              className="comet-body-s space-y-1 rounded-md border border-warning-box-text/40 bg-warning-box-bg p-3"
              data-testid="model-price-warnings"
            >
              <p className="comet-body-s-accented">Check these before saving</p>
              <ul className="list-disc space-y-1 pl-5">
                {warnings.map((warning, i) => (
                  <li key={i}>{warning.message}</li>
                ))}
              </ul>
            </div>
          )}
        </div>

        <div className="space-y-3 border-t px-6 py-4">
          {canEdit && (
            <div className="comet-body-xs flex items-center gap-2 rounded-md bg-[var(--tag-blue-bg)] px-3 py-2 text-[var(--tag-blue-text)]">
              <Info className="size-3.5 shrink-0" />
              New prices apply to new spans only. Existing spans keep their
              estimated cost.
            </div>
          )}
          <div className="flex items-center gap-2">
            {canEdit ? (
              <>
                <Button
                  size="sm"
                  onClick={() => save(false)}
                  disabled={isSaving}
                >
                  {warnings.length
                    ? "Save anyway"
                    : isNew
                      ? "Add model"
                      : "Save changes"}
                </Button>
                <Button size="sm" variant="ghost" onClick={requestClose}>
                  Cancel
                </Button>
                {type === "override" && loaded.row && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="ml-auto"
                    disabled={isSaving}
                    onClick={() => save(true)}
                  >
                    <RotateCcw className="mr-1.5 size-3.5" />
                    Restore all defaults
                  </Button>
                )}
                {type === "custom" && loaded.row && (
                  <Button
                    size="sm"
                    variant="outline"
                    className="ml-auto"
                    disabled={isSaving}
                    onClick={() => setConfirming("remove")}
                  >
                    <Trash2 className="mr-1.5 size-3.5" />
                    Remove model
                  </Button>
                )}
              </>
            ) : (
              <p className="comet-body-xs text-muted-slate">
                Only organization admins can change prices.
              </p>
            )}
          </div>
        </div>
      </SheetContent>
      <ConfirmDialog
        open={confirming === "discard"}
        setOpen={(next) => !next && setConfirming(undefined)}
        onConfirm={() => setOpen(false)}
        title="Discard your changes?"
        description="The prices and note you changed here aren't saved yet."
        confirmText="Discard"
        cancelText="Keep editing"
        confirmButtonVariant="destructive"
      />
      <ConfirmDialog
        open={confirming === "remove"}
        setOpen={(next) => !next && setConfirming(undefined)}
        onConfirm={() => save(true)}
        title={`Remove ${title}?`}
        description="Spans saved after this won't have a price for this model. Spans already saved keep their cost."
        confirmText="Remove model"
        confirmButtonVariant="destructive"
      />
    </Sheet>
  );
};

export default ModelPriceDrawer;
