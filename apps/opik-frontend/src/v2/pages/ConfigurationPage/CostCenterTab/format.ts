import dayjs from "dayjs";
import { CatalogType } from "@/lib/model-prices";
import { TagProps } from "@/ui/tag";

export const PRICE_UNIT_SUFFIX = "/1M tok";

export const formatPrice = (perMillion?: string) => {
  if (!perMillion) {
    return undefined;
  }
  const value = Number(perMillion);
  if (value >= 1) {
    return `$${value.toLocaleString("en-US", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })}`;
  }
  if (value === 0 || value >= 0.001) {
    return `$${value.toFixed(3)}`;
  }
  return `$${Number(value.toPrecision(2))}`;
};

export const formatDay = (iso?: string) =>
  iso ? dayjs(iso).format("D MMM YYYY") : undefined;

export const TYPE_TAG_VARIANT: Record<CatalogType, TagProps["variant"]> = {
  default: "gray",
  override: "yellow",
  custom: "green",
};

export const MODE_LABELS: Record<string, string> = {
  chat: "Chat",
  completion: "Completion",
  embedding: "Embedding",
  responses: "Responses",
  rerank: "Rerank",
  audio_transcription: "Audio",
  audio_speech: "Speech",
  image_generation: "Image",
  moderation: "Moderation",
};
