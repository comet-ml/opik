import { useMemo } from "react";
import useConfigHistoryList from "./useConfigHistoryList";

type UseConfigVersionMapOptions = {
  enabled?: boolean;
};

export default function useConfigVersionMap(
  projectId: string,
  options: UseConfigVersionMapOptions = {},
) {
  const { enabled = true } = options;

  const { data } = useConfigHistoryList(
    { projectId, page: 1, size: 1000 },
    { enabled: Boolean(projectId) && enabled },
  );

  return useMemo<Record<string, number>>(() => {
    if (!data) return {};
    const total = data.total;
    return Object.fromEntries(
      data.content.map((item, index) => [item.id, total - index]),
    );
  }, [data]);
}
