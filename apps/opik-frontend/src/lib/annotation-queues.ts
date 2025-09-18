import { Thread, Trace } from "@/types/traces";
import get from "lodash/get";

export const generateSMEURL = (workspace: string, id: string): string => {
  const basePath = import.meta.env.VITE_BASE_URL || "/";
  const relativePath = `${workspace}/sme?queueId=${id}`;

  const normalizedBasePath =
    basePath === "/" ? "" : basePath.replace(/\/$/, "");
  const fullPath = `${normalizedBasePath}/${relativePath}`;
  return new URL(fullPath, window.location.origin).toString();
};

export const getAnnotationQueueItemId = (item: Trace | Thread) =>
  get(item, "thread_model_id", item.id);
