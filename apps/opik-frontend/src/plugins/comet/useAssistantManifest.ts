import { useQuery } from "@tanstack/react-query";
import {
  ASSISTANT_DEV_BASE_URL,
  IS_ASSISTANT_DEV,
} from "@/plugins/comet/constants/assistant";

// Pod may serve /console/manifest.json before /health/ready flips — retry
// with backoff so transient 404/503 during warmup don't permanently fail.
// Budget (~140s) exceeds the 2 min health-poll timeout so manifest doesn't
// give up before health polling does.
const MANIFEST_RETRY_COUNT = 30;
const MANIFEST_RETRY_BASE_DELAY_MS = 500;
const MANIFEST_RETRY_MAX_DELAY_MS = 5000;

interface AssistantManifest {
  js: string;
  css?: string;
  shell: string;
  ver: string;
}

export interface AssistantMeta {
  scriptUrl: string;
  cssUrl?: string;
  shellUrl: string;
  version: string;
}

export function resolveManifestUrl(backendUrl: string | null): string | null {
  if (ASSISTANT_DEV_BASE_URL) return `${ASSISTANT_DEV_BASE_URL}/manifest.json`;
  if (backendUrl) return `${backendUrl}/console/manifest.json`;
  return null;
}

const DEV_META: AssistantMeta = {
  scriptUrl: "/assistant/assistant.js",
  cssUrl: "/assistant/assistant.css",
  shellUrl: "/assistant/shell",
  version: "dev",
};

export default function useAssistantManifest(
  backendUrl: string | null,
): AssistantMeta | null {
  const manifestUrl = resolveManifestUrl(backendUrl);

  const manifestBase = manifestUrl
    ? manifestUrl.substring(0, manifestUrl.lastIndexOf("/"))
    : null;

  const { data } = useQuery<AssistantMeta>({
    queryKey: ["assistant-manifest", manifestUrl],
    queryFn: async () => {
      const res = await fetch(manifestUrl!);
      if (!res.ok) throw new Error(`manifest ${res.status}`);
      const manifest: AssistantManifest = await res.json();
      return {
        scriptUrl: `${manifestBase}/${manifest.js}`,
        cssUrl: manifest.css ? `${manifestBase}/${manifest.css}` : undefined,
        shellUrl: `${manifestBase}/${manifest.shell}`,
        version: manifest.ver,
      };
    },
    enabled: !IS_ASSISTANT_DEV && !!manifestUrl,
    staleTime: Infinity,
    retry: MANIFEST_RETRY_COUNT,
    retryDelay: (attempt) =>
      Math.min(
        MANIFEST_RETRY_BASE_DELAY_MS * 2 ** attempt,
        MANIFEST_RETRY_MAX_DELAY_MS,
      ),
  });

  if (IS_ASSISTANT_DEV) return DEV_META;

  return data ?? null;
}
