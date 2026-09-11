import api from "@/api/api";
import { useQuery } from "@tanstack/react-query";

const PING_FETCHING_TIMEOUT_SECONDS = 5;
const CONNECTED_PING_REFETCH_INTERVAL_SECONDS = 10;
const DISCONNECTED_PING_REFETCH_INTERVAL_SECONDS = 5;

interface IsAlivePingResponse {
  healthy: boolean;
  rtt: number;
}

const getPing = async (): Promise<IsAlivePingResponse> => {
  const startTime = performance.now();

  const { data } = await api.get<IsAlivePingResponse>("/is-alive/ping", {
    timeout: PING_FETCHING_TIMEOUT_SECONDS * 1000,
  });

  const endTime = performance.now();

  const rtt = endTime - startTime;

  return { ...data, rtt };
};

export const usePingBackend = (isNetworkOnline: boolean) =>
  useQuery({
    queryKey: ["backend-ping"],
    queryFn: getPing,
    enabled: isNetworkOnline,
    retryDelay: 1000,
    refetchInterval: (query) => {
      const { error: isError, data } = query.state;
      const isConnected = !isError && data?.healthy;

      return isConnected
        ? CONNECTED_PING_REFETCH_INTERVAL_SECONDS * 1000
        : DISCONNECTED_PING_REFETCH_INTERVAL_SECONDS * 1000;
    },
    refetchOnReconnect: true,
  });
