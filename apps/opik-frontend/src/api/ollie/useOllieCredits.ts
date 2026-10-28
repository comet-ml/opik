import { useQuery } from "@tanstack/react-query";
import { OLLIE_CREDITS_KEY } from "@/api/api";
import { useActiveWorkspaceName } from "@/store/AppStore";
import usePluginsStore from "@/store/PluginsStore";

// Only the Comet plugin can answer; without it (OSS) nothing bills Ollie runs, so data stays undefined.
export default function useOllieCredits() {
  const workspaceName = useActiveWorkspaceName();
  const getOllieCredits = usePluginsStore((state) => state.getOllieCredits);

  return useQuery({
    queryKey: [OLLIE_CREDITS_KEY, { workspaceName }],
    queryFn: ({ signal }) => getOllieCredits!(workspaceName, signal),
    enabled: Boolean(getOllieCredits && workspaceName),
    staleTime: 10000,
  });
}
