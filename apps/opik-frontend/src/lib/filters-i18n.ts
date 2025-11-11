import { TFunction } from "i18next";
import { FilterOperator } from "@/types/filters";
import { COLUMN_TYPE, DropdownOption } from "@/types/shared";

export const getOperatorLabel = (
  t: TFunction,
  operator: FilterOperator,
): string => {
  switch (operator) {
    case "=":
      return "=";
    case "contains":
      return t("filters.operators.contains");
    case "not_contains":
      return t("filters.operators.notContains");
    case "starts_with":
      return t("filters.operators.startsWith");
    case "ends_with":
      return t("filters.operators.endsWith");
    case ">":
      return ">";
    case ">=":
      return ">=";
    case "<":
      return "<";
    case "<=":
      return "<=";
    case "is_empty":
      return t("filters.operators.isEmpty");
    case "is_not_empty":
      return t("filters.operators.isNotEmpty");
    default:
      return operator;
  }
};

export const getOperatorsMap = (
  t: TFunction,
): Record<COLUMN_TYPE, DropdownOption<FilterOperator>[]> => {
  return {
    [COLUMN_TYPE.string]: [
      { label: "=", value: "=" },
      { label: t("filters.operators.contains"), value: "contains" },
      { label: t("filters.operators.notContains"), value: "not_contains" },
      { label: t("filters.operators.startsWith"), value: "starts_with" },
      { label: t("filters.operators.endsWith"), value: "ends_with" },
    ],
    [COLUMN_TYPE.number]: [
      { label: "=", value: "=" },
      { label: ">", value: ">" },
      { label: ">=", value: ">=" },
      { label: "<", value: "<" },
      { label: "<=", value: "<=" },
    ],
    [COLUMN_TYPE.cost]: [
      { label: "=", value: "=" },
      { label: ">", value: ">" },
      { label: ">=", value: ">=" },
      { label: "<", value: "<" },
      { label: "<=", value: "<=" },
    ],
    [COLUMN_TYPE.duration]: [
      { label: "=", value: "=" },
      { label: ">", value: ">" },
      { label: ">=", value: ">=" },
      { label: "<", value: "<" },
      { label: "<=", value: "<=" },
    ],
    [COLUMN_TYPE.list]: [
      { label: t("filters.operators.contains"), value: "contains" },
    ],
    [COLUMN_TYPE.time]: [
      { label: "=", value: "=" },
      { label: ">", value: ">" },
      { label: ">=", value: ">=" },
      { label: "<", value: "<" },
      { label: "<=", value: "<=" },
    ],
    [COLUMN_TYPE.dictionary]: [
      { label: "=", value: "=" },
      { label: t("filters.operators.contains"), value: "contains" },
      { label: t("filters.operators.notContains"), value: "not_contains" },
      { label: t("filters.operators.startsWith"), value: "starts_with" },
      { label: t("filters.operators.endsWith"), value: "ends_with" },
      { label: ">", value: ">" },
      { label: "<", value: "<" },
    ],
    [COLUMN_TYPE.numberDictionary]: [
      { label: "=", value: "=" },
      { label: ">", value: ">" },
      { label: ">=", value: ">=" },
      { label: "<", value: "<" },
      { label: "<=", value: "<=" },
      { label: t("filters.operators.isEmpty"), value: "is_empty" },
      { label: t("filters.operators.isNotEmpty"), value: "is_not_empty" },
    ],
    [COLUMN_TYPE.category]: [{ label: "=", value: "=" }],
    [COLUMN_TYPE.errors]: [
      { label: t("filters.operators.isEmpty"), value: "is_empty" },
      { label: t("filters.operators.isNotEmpty"), value: "is_not_empty" },
    ],
  };
};

