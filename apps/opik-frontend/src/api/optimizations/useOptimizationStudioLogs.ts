import { useQuery } from "@tanstack/react-query";
import axios from "axios";
import api, {
  OPTIMIZATION_KEY,
  OPTIMIZATIONS_STUDIO_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";

type UseOptimizationStudioLogsParams = {
  optimizationId: string;
};

export type OptimizationStudioLogsUrlResponse = {
  url: string;
  expires_at: string;
};

export type UseOptimizationStudioLogsResponse = {
  content: string;
  url: string | null;
  expiresAt: string | null;
};

/**
 * Decompresses gzipped data using the browser's native DecompressionStream API.
 */
const decompressGzip = async (compressedData: ArrayBuffer): Promise<string> => {
  const stream = new Response(compressedData).body;
  if (!stream) {
    throw new Error("Failed to create stream from compressed data");
  }

  const decompressedStream = stream.pipeThrough(
    new DecompressionStream("gzip"),
  );
  const decompressedResponse = new Response(decompressedStream);
  return decompressedResponse.text();
};

// Cache for presigned URLs: optimizationId -> { url, expiresAt }
const urlCache = new Map<string, { url: string; expiresAt: Date }>();

const getOptimizationStudioLogs = async ({
  optimizationId,
}: UseOptimizationStudioLogsParams): Promise<UseOptimizationStudioLogsResponse> => {
  try {
    // Check if we have a cached URL that's still valid (refresh if within 5 minutes of expiration)
    const cached = urlCache.get(optimizationId);
    const now = new Date();
    const fiveMinutesFromNow = new Date(now.getTime() + 5 * 60 * 1000);

    let presignedUrl: string;
    let expiresAt: string;

    if (cached && cached.expiresAt > fiveMinutesFromNow) {
      // Use cached URL - it's still valid
      presignedUrl = cached.url;
      expiresAt = cached.expiresAt.toISOString();
    } else {
      // Fetch new presigned URL from backend
      const { data: urlResponse } =
        await api.get<OptimizationStudioLogsUrlResponse>(
          `${OPTIMIZATIONS_STUDIO_REST_ENDPOINT}${optimizationId}/logs`,
        );

      presignedUrl = urlResponse.url;
      expiresAt = urlResponse.expires_at;

      // Cache the new URL with its actual expiration time
      urlCache.set(optimizationId, {
        url: presignedUrl,
        expiresAt: new Date(expiresAt),
      });
    }

    // Fetch the gzipped log file as binary data using cached or new URL
    const { data: compressedData } = await axios.get<ArrayBuffer>(
      presignedUrl,
      {
        responseType: "arraybuffer",
      },
    );

    // Decompress the gzipped content
    const logContent = await decompressGzip(compressedData);

    return {
      content: logContent,
      url: presignedUrl,
      expiresAt,
    };
  } catch {
    // Clear cached URL on error so next request fetches a fresh one
    urlCache.delete(optimizationId);
    return {
      content: "",
      url: null,
      expiresAt: null,
    };
  }
};

export default function useOptimizationStudioLogs(
  params: UseOptimizationStudioLogsParams,
  options?: QueryConfig<UseOptimizationStudioLogsResponse>,
) {
  return useQuery({
    queryKey: [OPTIMIZATION_KEY, { ...params, type: "logs" }],
    queryFn: () => getOptimizationStudioLogs(params),
    // The Map cache handles URL caching - we fetch content frequently but URL only when needed
    // React Query's cache persists across tab switches
    ...options,
  });
}
