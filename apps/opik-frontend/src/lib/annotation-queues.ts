import { Thread, Trace } from "@/types/traces";
import get from "lodash/get";
import { isObjectThread } from "@/lib/traces";

export const generateSMEURL = (workspace: string, id: string): string => {
  const basePath = import.meta.env.VITE_BASE_URL || "/";
  const relativePath = `${workspace}/sme?queueId=${id}`;

  const normalizedBasePath =
    basePath === "/" ? "" : basePath.replace(/\/$/, "");
  const fullPath = `${normalizedBasePath}/${relativePath}`;
  return new URL(fullPath, window.location.origin).toString();
};

export const generateTracesURL = (
  workspace: string,
  projectId: string,
  type: "traces" | "threads",
  id: string,
): string => {
  const basePath = import.meta.env.VITE_BASE_URL || "/";
  const queryParam = type === "traces" ? `trace=${id}` : `thread=${id}`;
  const relativePath = `${workspace}/projects/${projectId}/traces?type=${type}&${queryParam}`;

  const normalizedBasePath =
    basePath === "/" ? "" : basePath.replace(/\/$/, "");
  const fullPath = `${normalizedBasePath}/${relativePath}`;
  return new URL(fullPath, window.location.origin).toString();
};

export const getAnnotationQueueItemId = (item: Trace | Thread) =>
  isObjectThread(item) ? get(item, "thread_model_id", "") : get(item, "id", "");
