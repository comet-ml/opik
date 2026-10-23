import { LlmModelsByProvider } from "@/api/llm/useLlmModels";

export const RETIRING_SOON_DAYS = 30;

const DAY_MS = 24 * 60 * 60 * 1000;

export type ModelRetirement = {
  date: string;
  retired: boolean;
};

// A Gemini API model and its Vertex AI copy share a bare id but have their own dates, so only the
// provider's own list is searched.
export const getModelRetirement = (
  registry: LlmModelsByProvider | undefined,
  model: string | undefined | null,
  provider: string | undefined | null,
  today: Date = new Date(),
): ModelRetirement | undefined => {
  if (!registry || !model || !provider) return undefined;

  const models = registry[provider] ?? [];
  const entry =
    models.find((m) => m.qualified_name === model) ??
    models.find((m) => m.id === model);
  const date = entry?.retires_on;
  if (!date) return undefined;

  const retiresAt = Date.parse(date);
  if (Number.isNaN(retiresAt)) return undefined;

  const startOfToday = Date.UTC(
    today.getUTCFullYear(),
    today.getUTCMonth(),
    today.getUTCDate(),
  );
  const daysLeft = (retiresAt - startOfToday) / DAY_MS;
  if (daysLeft > RETIRING_SOON_DAYS) return undefined;

  return { date, retired: daysLeft <= 0 };
};

export const formatRetirementDate = (date: string): string =>
  new Date(Date.parse(date)).toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
