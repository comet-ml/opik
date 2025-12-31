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
    ...options,
  });
}
