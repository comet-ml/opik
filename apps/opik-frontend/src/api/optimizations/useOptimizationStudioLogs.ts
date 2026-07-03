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

const getOptimizationStudioLogs = async ({
  optimizationId,
}: UseOptimizationStudioLogsParams): Promise<UseOptimizationStudioLogsResponse> => {
  try {
    // Fetch presigned URL from backend
    const { data: urlResponse } =
      await api.get<OptimizationStudioLogsUrlResponse>(
        `${OPTIMIZATIONS_STUDIO_REST_ENDPOINT}${optimizationId}/logs`,
      );

    const presignedUrl = urlResponse.url;
    const expiresAt = urlResponse.expires_at;

    // Fetch the gzipped log file as binary data
    let compressedData: ArrayBuffer;
    try {
      ({ data: compressedData } = await axios.get<ArrayBuffer>(presignedUrl, {
        responseType: "arraybuffer",
      }));
    } catch (presignedError) {
      // A 404 means the log file genuinely doesn't exist — let the outer
      // handler map it to "no logs".
      if (
        axios.isAxiosError(presignedError) &&
        presignedError.response?.status === 404
      ) {
        throw presignedError;
      }
      // Anything else usually means the presigned host isn't reachable from
      // the browser (e.g. in-cluster MinIO) or CORS blocked the request.
      // Fall back to streaming the same file through the backend, which
      // serves it for MinIO installations (403 on real S3 → surfaces as a
      // real error, since there the presigned URL should have worked).
      ({ data: compressedData } = await api.get<ArrayBuffer>(
        `${OPTIMIZATIONS_STUDIO_REST_ENDPOINT}${optimizationId}/logs/download`,
        { responseType: "arraybuffer" },
      ));
    }

    // Decompress the gzipped content
    const logContent = await decompressGzip(compressedData);

    return {
      content: logContent,
      url: presignedUrl,
      expiresAt,
    };
  } catch (error) {
    // A missing log file (404) just means logs aren't available yet — treat as
    // empty. Any other failure is a real fetch error and must surface instead
    // of being silently hidden as "no logs".
    if (axios.isAxiosError(error) && error.response?.status === 404) {
      return {
        content: "",
        url: null,
        expiresAt: null,
      };
    }
    throw error;
  }
};

export default function useOptimizationStudioLogs(
  params: UseOptimizationStudioLogsParams,
  options?: QueryConfig<UseOptimizationStudioLogsResponse>,
) {
  return useQuery({
    queryKey: [OPTIMIZATION_KEY, { ...params, type: "logs" }],
    queryFn: () => getOptimizationStudioLogs(params),
    ...options,
  });
}
