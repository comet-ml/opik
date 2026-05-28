import { Filter } from "@/types/filters";

export const trimValue = (raw: unknown): string => String(raw ?? "").trim();

export const toNumber = (raw: Filter["value"]): number | null => {
  if (typeof raw === "number") return Number.isFinite(raw) ? raw : null;
  if (typeof raw !== "string") return null;
  const s = raw.trim();
  if (s === "") return null;
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
};
