import axios from "axios";
import api from "../api";
import { Workspace } from "../types";

// The workspace point reads answer 404 for "no such workspace" and for "not visible to you" alike,
// and mark it with the backend's error body. A 404 without that body is the router saying the route
// is not there -- a frontend deployed ahead of its backend -- which is a failure, not an answer:
// reading it as "no workspace" would send every user to the private-project page.
export const isPointReadMiss = (error: unknown) =>
  axios.isAxiosError(error) &&
  error.response?.status === 404 &&
  typeof error.response?.data === "object" &&
  error.response?.data !== null &&
  "code" in error.response.data;

export const postWorkspacePointRead = async (
  url: string,
  body: Record<string, string>,
  signal?: AbortSignal,
): Promise<Workspace | null> => {
  try {
    const { data } = await api.post<Workspace>(url, body, { signal });
    return data;
  } catch (error) {
    if (isPointReadMiss(error)) return null;
    throw error;
  }
};

// "Not found" is already an answer rather than a failure, so retrying only delays a render that
// cannot proceed without the workspace anyway.
export const WORKSPACE_POINT_READ_QUERY_OPTIONS = {
  retry: false,
  staleTime: Infinity,
} as const;
