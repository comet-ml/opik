import dayjs from "dayjs";

export {
  PRICE_UNIT_SUFFIX,
  TYPE_TAG_VARIANT,
  formatPrice,
} from "@/shared/CostBreakdown/format";

export const MAX_QUICK_ACCESS = 8;

export const formatDay = (iso?: string) =>
  iso ? dayjs(iso).format("D MMM YYYY") : undefined;

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
