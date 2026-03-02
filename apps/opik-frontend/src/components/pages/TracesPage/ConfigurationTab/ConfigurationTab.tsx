import React, { useMemo } from "react";
import { StringParam, useQueryParam } from "use-query-params";

import Loader from "@/components/shared/Loader/Loader";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import useConfigHistoryListInfinite from "@/api/optimizer-configs/useConfigHistoryListInfinite";
import { ConfigHistoryItem } from "@/types/optimizer-configs";
import ConfigurationHistoryTimeline from "./ConfigurationHistoryTimeline";
import ConfigurationDetailView from "./ConfigurationDetailView";

type ConfigurationTabProps = {
  projectId: string;
};

const ConfigurationTab: React.FC<ConfigurationTabProps> = ({ projectId }) => {
  const [selectedId, setSelectedId] = useQueryParam("configId", StringParam, {
    updateType: "replaceIn",
  });

  const { data, isPending } = useConfigHistoryListInfinite({ projectId });

  const allRows = useMemo(
    () => data?.pages.flatMap((p) => p.content) ?? [],
    [data],
  );
  const total = data?.pages[0]?.total ?? 0;

  const selectedIndex = useMemo(() => {
    if (!selectedId) return 0;
    const idx = allRows.findIndex((r) => r.id === selectedId);
    return idx >= 0 ? idx : 0;
  }, [allRows, selectedId]);

  if (isPending) {
    return <Loader />;
  }

  if (allRows.length === 0) {
    return <DataTableNoData title="No configuration history" />;
  }

  const selectedItem = allRows[selectedIndex] as ConfigHistoryItem;

  return (
    <div className="flex min-h-[400px] gap-0">
      <div className="min-w-0 flex-1">
        {selectedItem ? (
          <ConfigurationDetailView
            item={selectedItem}
            version={total - selectedIndex}
            projectId={projectId}
            isLatest={selectedIndex === 0}
          />
        ) : (
          <div className="flex h-full items-center justify-center text-muted-slate">
            Select a version to view its configuration
          </div>
        )}
      </div>

      <div className="w-72 shrink-0 border-l">
        <h3>Version history</h3>
        <ConfigurationHistoryTimeline
          items={allRows}
          total={total}
          selectedIndex={selectedIndex}
          onSelect={(index) => setSelectedId(allRows[index]?.id ?? undefined)}
        />
      </div>
    </div>
  );
};

export default ConfigurationTab;
