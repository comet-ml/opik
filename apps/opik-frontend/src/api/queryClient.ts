import { QueryClient } from "@tanstack/react-query";
import { QUERY_CLIENT_DEFAULT_OPTIONS } from "@/api/queryRetry";

export const queryClient = new QueryClient({
  defaultOptions: QUERY_CLIENT_DEFAULT_OPTIONS,
});
