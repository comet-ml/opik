import React, { useMemo, useState } from "react";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import {
  ModelPriceKind,
  ModelPriceProblem,
  ModelPriceRow,
  PRICE_FIELDS,
  PRICE_LABELS,
  PriceField,
  identityOf,
  validateRow,
} from "@/lib/model-prices";

type FormState = {
  model: string;
  provider: string;
  kind: ModelPriceKind;
  prices: Record<PriceField, string>;
  aliasModel: string;
  aliasProvider: string;
  note: string;
};

const toFormState = (row?: ModelPriceRow): FormState => ({
  model: row?.model ?? "",
  provider: row?.provider ?? "",
  kind: row?.kind ?? "fixed",
  prices: {
    input_price: row?.input_price ?? "",
    output_price: row?.output_price ?? "",
    cache_read_price: row?.cache_read_price ?? "",
    cache_write_price: row?.cache_write_price ?? "",
  },
  aliasModel: row?.alias_of?.model ?? "",
  aliasProvider: row?.alias_of?.provider ?? "",
  note: row?.note ?? "",
});

const toRow = (form: FormState): ModelPriceRow => ({
  model: form.model,
  provider: form.provider,
  kind: form.kind,
  ...(form.kind === "alias"
    ? { alias_of: { model: form.aliasModel, provider: form.aliasProvider } }
    : form.prices),
  note: form.note,
});

type ModelPriceDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  row?: ModelPriceRow;
  takenIdentities: Set<string>;
  onSubmit: (row: ModelPriceRow) => void;
};

const FieldError: React.FC<{
  problems: ModelPriceProblem[];
  field: string;
}> = ({ problems, field }) => {
  const problem = problems.find((candidate) => candidate.field === field);
  return problem ? (
    <p role="alert" className="comet-body-xs text-destructive">
      {problem.message}
    </p>
  ) : null;
};

const ModelPriceDialog: React.FC<ModelPriceDialogProps> = ({
  open,
  setOpen,
  row,
  takenIdentities,
  onSubmit,
}) => {
  const [form, setForm] = useState<FormState>(() => toFormState(row));
  const [submitted, setSubmitted] = useState(false);
  const isEdit = Boolean(row);

  const problems = useMemo(() => {
    const candidate = toRow(form);
    const found = validateRow(candidate);
    if (form.model.trim() && takenIdentities.has(identityOf(candidate))) {
      found.push({
        field: "model",
        code: "duplicate_row",
        message: "The list already has a price for this model on this provider",
      });
    }
    return found;
  }, [form, takenIdentities]);
  const shownProblems = submitted ? problems : [];

  const update = (changes: Partial<FormState>) =>
    setForm((current) => ({ ...current, ...changes }));

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitted(true);
    if (problems.length) return;
    onSubmit(toRow(form));
    setOpen(false);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[600px]">
        <DialogHeader>
          <DialogTitle>{isEdit ? "Edit price" : "Add price"}</DialogTitle>
        </DialogHeader>
        <form onSubmit={submit} className="contents">
          <DialogAutoScrollBody>
            <div className="flex flex-col gap-5 pb-2">
              <div className="flex flex-col gap-2">
                <Label htmlFor="modelPriceModel">
                  Model name, as your spans send it
                </Label>
                <Input
                  id="modelPriceModel"
                  className="comet-code"
                  placeholder="e.g. gpt-4o or custom-llm/Bifrost/gpt-5-mini"
                  value={form.model}
                  onChange={(event) => update({ model: event.target.value })}
                />
                <FieldError problems={shownProblems} field="model" />
              </div>

              <div className="flex flex-col gap-2">
                <Label htmlFor="modelPriceProvider">Provider</Label>
                <Input
                  id="modelPriceProvider"
                  placeholder="Any provider"
                  value={form.provider}
                  onChange={(event) => update({ provider: event.target.value })}
                />
                <p className="comet-body-xs text-light-slate">
                  Leave it empty to price this model on every provider.
                </p>
                <FieldError problems={shownProblems} field="provider" />
              </div>

              <div className="flex flex-col gap-2">
                <Label>Price</Label>
                <ToggleGroup
                  type="single"
                  variant="outline"
                  className="w-fit"
                  value={form.kind}
                  onValueChange={(value) =>
                    value && update({ kind: value as ModelPriceKind })
                  }
                >
                  <ToggleGroupItem value="fixed">Our own price</ToggleGroupItem>
                  <ToggleGroupItem value="alias">
                    Same as another model
                  </ToggleGroupItem>
                </ToggleGroup>
              </div>

              {form.kind === "fixed" ? (
                <div className="flex flex-col gap-2">
                  <Label>USD per 1M tokens</Label>
                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    {PRICE_FIELDS.map((field) => (
                      <div key={field} className="flex flex-col gap-1">
                        <Label
                          htmlFor={`modelPrice-${field}`}
                          className="comet-body-xs text-muted-slate"
                        >
                          {PRICE_LABELS[field]}
                        </Label>
                        <Input
                          id={`modelPrice-${field}`}
                          inputMode="decimal"
                          className="comet-code"
                          placeholder="built-in"
                          value={form.prices[field]}
                          onChange={(event) =>
                            update({
                              prices: {
                                ...form.prices,
                                [field]: event.target.value,
                              },
                            })
                          }
                        />
                        <FieldError problems={shownProblems} field={field} />
                      </div>
                    ))}
                  </div>
                  <p className="comet-body-xs text-light-slate">
                    Leave a price empty to use the built-in rate. 0 makes it
                    free.
                  </p>
                  {shownProblems
                    .filter((problem) => problem.field == null)
                    .map((problem) => (
                      <p
                        key={problem.code}
                        role="alert"
                        className="comet-body-xs text-destructive"
                      >
                        {problem.message}
                      </p>
                    ))}
                </div>
              ) : (
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  <div className="flex flex-col gap-2">
                    <Label htmlFor="modelPriceAliasModel">Priced like</Label>
                    <Input
                      id="modelPriceAliasModel"
                      className="comet-code"
                      placeholder="e.g. gpt-4o"
                      value={form.aliasModel}
                      onChange={(event) =>
                        update({ aliasModel: event.target.value })
                      }
                    />
                    <FieldError
                      problems={shownProblems}
                      field="alias_of.model"
                    />
                  </div>
                  <div className="flex flex-col gap-2">
                    <Label htmlFor="modelPriceAliasProvider">On provider</Label>
                    <Input
                      id="modelPriceAliasProvider"
                      placeholder="The span's own provider"
                      value={form.aliasProvider}
                      onChange={(event) =>
                        update({ aliasProvider: event.target.value })
                      }
                    />
                    <FieldError
                      problems={shownProblems}
                      field="alias_of.provider"
                    />
                  </div>
                </div>
              )}

              <div className="flex flex-col gap-2">
                <Label htmlFor="modelPriceNote">Note (optional)</Label>
                <Input
                  id="modelPriceNote"
                  placeholder="e.g. Contract rate, 2026"
                  value={form.note}
                  onChange={(event) => update({ note: event.target.value })}
                />
                <FieldError problems={shownProblems} field="note" />
              </div>
            </div>
          </DialogAutoScrollBody>
          <DialogFooter className="items-center gap-3 sm:justify-between">
            <p className="comet-body-xs text-light-slate">
              Nothing is saved until you review and save the list.
            </p>
            <div className="flex gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => setOpen(false)}
              >
                Cancel
              </Button>
              <Button type="submit">
                {isEdit ? "Update row" : "Add to the list"}
              </Button>
            </div>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
};

export default ModelPriceDialog;
