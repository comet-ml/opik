import React, { useMemo, useState } from "react";

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
  const [selectedIndex, setSelectedIndex] = useState<number>(0);

  const { data, isPending } = useConfigHistoryListInfinite({ projectId });

  const allRows = useMemo(
    () => data?.pages.flatMap((p) => p.content) ?? [],
    [data],
  );
  const total = data?.pages[0]?.total ?? 0;

  if (isPending) {
    return <Loader />;
  }

  if (allRows.length === 0) {
    return <DataTableNoData title="No configuration history" />;
  }

  const selectedItem = allRows[selectedIndex] as ConfigHistoryItem;

  return (
    <div className="flex min-h-[400px] gap-0">
      <div className="w-72 shrink-0 border-r">
        <ConfigurationHistoryTimeline
          items={allRows}
          total={total}
          selectedIndex={selectedIndex}
          onSelect={setSelectedIndex}
        />
      </div>

      <div className="min-w-0 flex-1">
        {selectedItem ? (
          <ConfigurationDetailView item={selectedItem} version={total - selectedIndex} projectId={projectId} />
        ) : (
          <div className="flex h-full items-center justify-center text-muted-slate">
            Select a version to view its configuration
          </div>
        )}
      </div>
    </div>
  );
};

export default ConfigurationTab;
