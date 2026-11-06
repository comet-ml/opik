import type { ModelPriceProblem, ModelPriceRow, PriceField } from "./types";
import { PRICE_FIELDS } from "./types";
import type { ModelPricesDraft } from "./draft";
import { inSaveOrder } from "./draft";
import { canonicalPrice, identityOf, isDecimal } from "./rows";

export const MAX_NAME_LENGTH = 255;
export const MAX_NOTE_LENGTH = 500;
export const MAX_DECIMALS = 10;
export const MAX_PRICE_PER_MILLION = 100000;

const problem = (
  field: string | null,
  code: string,
  message: string,
): ModelPriceProblem => ({ row: null, field, code, message });

// Same range as Java's Character.isISOControl, which the backend uses.
const hasControlCharacters = (value: string) =>
  Array.from(value).some((character) => {
    const code = character.charCodeAt(0);
    return code < 32 || (code >= 127 && code <= 159);
  });

const isOverMaximum = (canonical: string) => {
  const [integer, fraction = ""] = canonical.split(".");
  if (integer.length > String(MAX_PRICE_PER_MILLION).length) {
    return true;
  }
  const whole = Number(integer);
  return (
    whole > MAX_PRICE_PER_MILLION ||
    (whole === MAX_PRICE_PER_MILLION && fraction.length > 0)
  );
};

export const validatePrice = (
  field: PriceField,
  value?: string | null,
): ModelPriceProblem | undefined => {
  const trimmed = (value ?? "").trim();
  if (!trimmed) {
    return undefined;
  }
  if (!isDecimal(trimmed)) {
    return problem(field, "not_a_number", "Type a plain number, like 2.50");
  }
  const canonical = canonicalPrice(trimmed);
  if (canonical.startsWith("-")) {
    return problem(field, "negative_price", "Can't be negative");
  }
  if ((canonical.split(".")[1] ?? "").length > MAX_DECIMALS) {
    return problem(
      field,
      "too_many_decimals",
      `At most ${MAX_DECIMALS} decimals`,
    );
  }
  if (isOverMaximum(canonical)) {
    return problem(
      field,
      "price_too_high",
      `At most ${MAX_PRICE_PER_MILLION.toLocaleString("en-US")} per 1M tokens`,
    );
  }
  const isCachePrice =
    field === "cache_read_price" || field === "cache_write_price";
  if (isCachePrice && canonical === "0") {
    return problem(
      field,
      "cache_price_not_positive",
      "Must be above 0. Leave it empty to use the built-in cache price",
    );
  }
  return undefined;
};

const validateName = (
  field: string,
  value: string,
): ModelPriceProblem | undefined => {
  if (value.length > MAX_NAME_LENGTH) {
    return problem(
      field,
      "name_too_long",
      `At most ${MAX_NAME_LENGTH} characters`,
    );
  }
  if (hasControlCharacters(value)) {
    return problem(field, "control_characters", "Remove the hidden characters");
  }
  return undefined;
};

export const validateRow = (row: ModelPriceRow): ModelPriceProblem[] => {
  const problems: ModelPriceProblem[] = [];
  const add = (found?: ModelPriceProblem) => found && problems.push(found);
  const model = (row.model ?? "").trim();
  const provider = (row.provider ?? "").trim();

  if (!model) {
    add(problem("model", "missing_model", "Every row needs a model"));
  }
  add(validateName("model", model));
  add(validateName("provider", provider));

  if (row.kind === "alias") {
    const target = (row.alias_of?.model ?? "").trim();
    const targetProvider = (row.alias_of?.provider ?? "").trim();
    if (!target) {
      add(
        problem(
          "alias_of.model",
          "missing_alias_target",
          "Pick the model it prices like",
        ),
      );
    } else if (
      target.toLowerCase() === model.toLowerCase() &&
      (!targetProvider ||
        targetProvider.toLowerCase() === provider.toLowerCase())
    ) {
      add(
        problem(
          "alias_of.model",
          "alias_target_is_itself",
          "A model can't be priced like itself",
        ),
      );
    }
    add(validateName("alias_of.model", target));
    add(validateName("alias_of.provider", targetProvider));
  } else {
    PRICE_FIELDS.forEach((field) => add(validatePrice(field, row[field])));
    if (PRICE_FIELDS.every((field) => !(row[field] ?? "").trim())) {
      add(problem(null, "no_price", "Set at least one price"));
    }
  }

  if ((row.note ?? "").trim().length > MAX_NOTE_LENGTH) {
    add(
      problem("note", "note_too_long", `At most ${MAX_NOTE_LENGTH} characters`),
    );
  }
  return problems;
};

export const validateDraft = (
  draft: ModelPricesDraft,
): Record<string, ModelPriceProblem[]> => {
  const problems: Record<string, ModelPriceProblem[]> = {};
  const firstKeyByIdentity = new Map<string, string>();
  inSaveOrder(draft.rows).forEach((draftRow) => {
    if (draftRow.deleted) {
      return;
    }
    const rowProblems = validateRow(draftRow.row);
    const identity = identityOf(draftRow.row);
    if (draftRow.row.model?.trim() && firstKeyByIdentity.has(identity)) {
      rowProblems.push(
        problem(
          "model",
          "duplicate_row",
          "Another row already prices this model on this provider",
        ),
      );
    } else {
      firstKeyByIdentity.set(identity, draftRow.key);
    }
    if (rowProblems.length) {
      problems[draftRow.key] = rowProblems;
    }
  });
  return problems;
};
