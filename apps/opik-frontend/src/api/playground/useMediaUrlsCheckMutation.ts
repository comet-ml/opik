import { useMutation } from "@tanstack/react-query";
import api, { MEDIA_URLS_REST_ENDPOINT } from "@/api/api";

export type MediaUrlCheckStatus =
  | "ok"
  | "skipped"
  | "invalid_url"
  | "blocked"
  | "unreachable"
  | "timeout"
  | "too_many_redirects"
  | "http_error"
  | "not_media";

export interface MediaUrlCheckResult {
  url: string;
  status: MediaUrlCheckStatus;
  http_status?: number;
  content_type?: string;
}

interface MediaUrlCheckResponse {
  results: MediaUrlCheckResult[];
}

// The backend caps its per-URL budget at 6 s so its answer always lands
// before this; past it the run starts without the check.
const MEDIA_URLS_CHECK_TIMEOUT_MS = 8000;

const checkMediaUrls = async (urls: string[]) => {
  const { data } = await api.post<MediaUrlCheckResponse>(
    `${MEDIA_URLS_REST_ENDPOINT}check`,
    { urls },
    { timeout: MEDIA_URLS_CHECK_TIMEOUT_MS },
  );
  return data.results;
};

const useMediaUrlsCheckMutation = () =>
  useMutation({
    mutationFn: checkMediaUrls,
  });

export default useMediaUrlsCheckMutation;
