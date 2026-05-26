import groupBy from "lodash/groupBy";
import partition from "lodash/partition";
import dayjs from "dayjs";
import customParseFormat from "dayjs/plugin/customParseFormat";
import { Filter, FilterOperator } from "@/types/filters";
import {
  TimeChipDefinition,
  TimeChipMode,
  TimeChipValue,
} from "@/shared/filter-chips/types";

dayjs.extend(customParseFormat);
import type { Period } from "@/ui/time-picker-utils";
import {
  drop,
  DroppedFilter,
  dropMany,
  FromFiltersResult,
} from "@/shared/filter-chips/lib/sanitizeFilters.types";

export const DATE_FORMAT = "YYYY/MM/DD";

export const TIME_DEFAULT_MODE: TimeChipMode = "after";

const TIME_EXACTLY_WINDOW_MS = 60_000;
const SUPPORTED_OPERATORS: ReadonlySet<FilterOperator> = new Set([
  "=",
  ">",
  ">=",
  "<",
  "<=",
]);

const toEpochMs = (raw: Filter["value"]): number | null => {
  if (typeof raw !== "string") return null;
  const d = dayjs(raw);
  return d.isValid() ? d.valueOf() : null;
};

export const isTimeApplied = (value: TimeChipValue | undefined): boolean => {
  if (!value) return false;
  switch (value.mode) {
    case "exactly":
      return Boolean(value.at);
    case "between":
      return Boolean(value.start) && Boolean(value.end);
    case "before":
      return Boolean(value.before);
    case "after":
      return Boolean(value.after);
  }
};

export const timeToFilters = (
  value: TimeChipValue | undefined,
  definition: TimeChipDefinition,
): Filter[] => {
  if (!isTimeApplied(value) || !value) return [];

  const field = definition.field;
  const type: Filter["type"] = definition.columnType ?? "";
  const key = "";
  const idStart = `${definition.id}_start`;
  const idEnd = `${definition.id}_end`;

  switch (value.mode) {
    case "exactly": {
      const start = value.at;
      const end = dayjs(value.at).add(1, "minute").toISOString();
      return [
        { id: idStart, field, type, key, operator: ">=", value: start },
        { id: idEnd, field, type, key, operator: "<=", value: end },
      ];
    }
    case "between":
      return [
        {
          id: idStart,
          field,
          type,
          key,
          operator: ">=",
          value: value.start,
        },
        {
          id: idEnd,
          field,
          type,
          key,
          operator: "<=",
          value: value.end,
        },
      ];
    case "before":
      return [
        {
          id: idEnd,
          field,
          type,
          key,
          operator: "<",
          value: value.before,
        },
      ];
    case "after":
      return [
        {
          id: idStart,
          field,
          type,
          key,
          operator: ">",
          value: value.after,
        },
      ];
  }
};

export const timeFromFilters = (
  candidates: Filter[],
): FromFiltersResult<TimeChipValue> => {
  const [supported, unsupported] = partition(candidates, (f) =>
    SUPPORTED_OPERATORS.has(f.operator as FilterOperator),
  );
  const dropped: DroppedFilter[] = dropMany(
    unsupported,
    "unsupported_operator",
  );
  const byOp = groupBy(supported, "operator");
  // Strict and inclusive bounds are equivalent at the chip's minute
  // granularity (one millisecond difference at the boundary). Treat them as
  // a single "lower bound" / "upper bound" class so the picker has a single
  // bounded-range path instead of a fingerprint check.
  const lowerBounds = [...(byOp[">="] ?? []), ...(byOp[">"] ?? [])];
  const upperBounds = [...(byOp["<="] ?? []), ...(byOp["<"] ?? [])];
  const eq = byOp["="] ?? [];

  if (lowerBounds.length > 0 && upperBounds.length > 0) {
    const start = String(lowerBounds[0].value);
    const end = String(upperBounds[0].value);
    const startMs = toEpochMs(start);
    const endMs = toEpochMs(end);
    if (startMs !== null && endMs !== null) {
      const extras = [...lowerBounds.slice(1), ...upperBounds.slice(1), ...eq];
      const value: TimeChipValue =
        endMs - startMs === TIME_EXACTLY_WINDOW_MS
          ? { mode: "exactly", at: start }
          : { mode: "between", start, end };
      return {
        value,
        used: [lowerBounds[0], upperBounds[0]],
        dropped: [...dropped, ...dropMany(extras, "duplicate_field")],
      };
    }
  }

  if (eq.length > 0) {
    const at = String(eq[0].value);
    if (toEpochMs(at) !== null) {
      const extras = [...eq.slice(1), ...lowerBounds, ...upperBounds];
      return {
        value: { mode: "exactly", at },
        used: [eq[0]],
        dropped: [...dropped, ...dropMany(extras, "duplicate_field")],
      };
    }
    dropped.push(drop(eq[0], "invalid_value"));
  }

  // Standalone lower bound → after; standalone upper bound → before.
  if (lowerBounds.length > 0) {
    const after = String(lowerBounds[0].value);
    if (toEpochMs(after) !== null) {
      return {
        value: { mode: "after", after },
        used: [lowerBounds[0]],
        dropped: [
          ...dropped,
          ...dropMany(lowerBounds.slice(1), "duplicate_field"),
        ],
      };
    }
  }

  if (upperBounds.length > 0) {
    const before = String(upperBounds[0].value);
    if (toEpochMs(before) !== null) {
      return {
        value: { mode: "before", before },
        used: [upperBounds[0]],
        dropped: [
          ...dropped,
          ...dropMany(upperBounds.slice(1), "duplicate_field"),
        ],
      };
    }
  }

  return { used: [], dropped };
};

const SUMMARY_DATETIME_FORMAT = "YYYY/MM/DD h:mm A";
const SUMMARY_DATE_FORMAT = "YYYY/MM/DD";

export const formatTimeSummary = (
  value: TimeChipValue | undefined,
): string | null => {
  if (!isTimeApplied(value) || !value) return null;
  const dt = (iso: string) => dayjs(iso).format(SUMMARY_DATETIME_FORMAT);
  switch (value.mode) {
    case "exactly":
      return `= ${dt(value.at)}`;
    case "between": {
      const start = dayjs(value.start).format(SUMMARY_DATE_FORMAT);
      const end = dayjs(value.end).format(SUMMARY_DATE_FORMAT);
      return `${start} – ${end}`;
    }
    case "before":
      return `< ${dt(value.before)}`;
    case "after":
      return `> ${dt(value.after)}`;
    default:
      return null;
  }
};

const to24h = (hour12: number, isPM: boolean): number => {
  const base = hour12 === 12 ? 0 : hour12;
  return isPM ? base + 12 : base;
};

/**
 * Parses flexible user time input. Supported shapes (case-insensitive):
 *   "7", "07"        → 7:00 (24h, or applies defaultPeriod when h ≤ 12)
 *   "730", "0730"    → 7:30
 *   "7:30"           → 7:30
 *   "7pm", "7:30pm"  → 19:00, 19:30 (explicit suffix overrides defaultPeriod)
 *   "19:30"          → 19:30
 *
 * When the input has no AM/PM suffix and an hour 1..12, `defaultPeriod` decides
 * how to interpret it. Hours 0 or 13..23 are unambiguous 24h.
 */
export const parseTimeInput = (
  raw: string,
  defaultPeriod?: Period,
): { hour: number; minute: number } | null => {
  let s = raw.trim().toLowerCase();
  if (s === "") return null;

  let isPM: boolean | null = null;
  const ampmMatch = s.match(/(am|pm|a|p)$/);
  if (ampmMatch) {
    isPM = ampmMatch[1].startsWith("p");
    s = s.slice(0, ampmMatch.index).trim();
  }

  let hStr: string;
  let mStr: string;
  if (s.includes(":")) {
    const [h, m = "0"] = s.split(":");
    hStr = h;
    mStr = m;
  } else if (s.length <= 2) {
    hStr = s;
    mStr = "0";
  } else {
    hStr = s.slice(0, s.length - 2);
    mStr = s.slice(s.length - 2);
  }

  const h = Number(hStr);
  const m = Number(mStr);
  if (!Number.isInteger(h) || !Number.isInteger(m)) return null;
  if (m < 0 || m > 59) return null;

  if (isPM === null) {
    if (h < 0 || h > 23) return null;
    if (defaultPeriod && h >= 1 && h <= 12) {
      return { hour: to24h(h, defaultPeriod === "PM"), minute: m };
    }
    return { hour: h, minute: m };
  }
  if (h < 1 || h > 12) return null;
  return { hour: to24h(h, isPM), minute: m };
};

/** Returns the numeric portion of a 12h time, e.g. (19, 30) → "7:30". */
export const formatTimeNumeric = (hour: number, minute: number): string => {
  const h12 = hour % 12 === 0 ? 12 : hour % 12;
  return `${h12}:${String(minute).padStart(2, "0")}`;
};

export const getPeriod = (hour: number): Period => (hour < 12 ? "AM" : "PM");

/** Shifts a 24h hour to the other AM/PM half. */
export const shiftHourForPeriod = (
  hour: number,
  nextPeriod: Period,
): number => {
  if (nextPeriod === "PM" && hour < 12) return hour + 12;
  if (nextPeriod === "AM" && hour >= 12) return hour - 12;
  return hour;
};

/**
 * Combines a parsed date (already at startOfDay) and a parsed time into an
 * ISO 8601 string. Returns null if either piece is missing.
 */
export const combineDateAndTime = (
  date: Date | null,
  time: { hour: number; minute: number } | null,
): string | null => {
  if (!date || !time) return null;
  return dayjs(date)
    .hour(time.hour)
    .minute(time.minute)
    .second(0)
    .millisecond(0)
    .toISOString();
};

// Accepted shapes (all year-first to avoid US/EU month-day ambiguity):
//   "2026/05/26", "2026/5/26", "2026-05-26", "2026-5-26",
//   "2026.05.26", "2026.5.26", "20260526"
const DATE_INPUT_FORMATS = [
  "YYYY/MM/DD",
  "YYYY/M/D",
  "YYYY-MM-DD",
  "YYYY-M-D",
  "YYYY.MM.DD",
  "YYYY.M.D",
  "YYYYMMDD",
];

export const parseDateInput = (raw: string): Date | null => {
  const trimmed = raw.trim();
  if (trimmed === "") return null;
  for (const fmt of DATE_INPUT_FORMATS) {
    const d = dayjs(trimmed, fmt, true);
    if (d.isValid()) return d.startOf("day").toDate();
  }
  return null;
};
