import { useMemo } from "react";
import { PROJECT_ICON_COUNT } from "@/constants/projectIcons";
import useProjectsList from "@/api/projects/useProjectsList";
import { useActiveWorkspaceName } from "@/store/AppStore";

export default function useProjectIconIndices() {
  const workspaceName = useActiveWorkspaceName();

  const { data } = useProjectsList({
    workspaceName,
    sorting: [{ id: "created_at", desc: false }],
    page: 1,
    size: 1000,
  });

  return useMemo(() => {
    if (!data?.content) return new Map<string, number>();
    return new Map(data.content.map((p, i) => [p.id, i % PROJECT_ICON_COUNT]));
  }, [data?.content]);
}
