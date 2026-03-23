import React, { useMemo, useState } from "react";
import { Book, Settings2 } from "lucide-react";
import { StringParam, useQueryParam } from "use-query-params";

import Loader from "@/shared/Loader/Loader";
import { buildDocsUrl } from "@/lib/utils";
import useConfigHistoryListInfinite from "@/api/agent-configs/useConfigHistoryListInfinite";
import { ConfigHistoryItem } from "@/types/agent-configs";
import ConfigurationHistoryTimeline from "./ConfigurationHistoryTimeline";
import ConfigurationDetailView from "./ConfigurationDetailView";
import ConfigurationEditView from "./ConfigurationEditView";

type ConfigurationTabProps = {
  projectId: string;
};

const ConfigurationTab: React.FC<ConfigurationTabProps> = ({ projectId }) => {
  const [selectedId, setSelectedId] = useQueryParam("configId", StringParam, {
    updateType: "replaceIn",
  });
  const [editItem, setEditItem] = useState<ConfigHistoryItem | null>(null);

  const { data, isPending, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useConfigHistoryListInfinite({ projectId });

  const allRows = useMemo(
    () => data?.pages.flatMap((p) => p.content) ?? [],
    [data],
  );

  const selectedIndex = useMemo(() => {
    if (!selectedId) return 0;
    const idx = allRows.findIndex((r) => r.id === selectedId);
    return idx >= 0 ? idx : 0;
  }, [allRows, selectedId]);

  if (isPending) {
    return <Loader />;
  }

  if (allRows.length === 0) {
    return (
      <div className="flex w-full justify-center p-6">
        <div className="flex w-full flex-col items-center rounded-md border px-6 py-14">
          <Settings2 className="mb-3 size-4 text-light-slate" />
          <h2 className="comet-title-xs">No agent configuration found</h2>
          <p className="comet-body-s mt-2 text-center text-muted-slate">
            This project doesn&apos;t include an agent configuration.
            <br />
            Configure your agent to track and edit prompts and parameters here.
          </p>
          <a
            href={buildDocsUrl("/tracing/log_agents")}
            target="_blank"
            rel="noreferrer"
            className="mt-4 flex items-center gap-1.5 text-sm text-primary hover:underline"
          >
            <Book className="size-4" />
            Learn how to configure your agent
          </a>
        </div>
      </div>
    );
  }

  const selectedItem = allRows[selectedIndex] as ConfigHistoryItem;

  if (editItem) {
    return (
      <div className="w-[70vw]">
        <ConfigurationEditView
          item={editItem}
          projectId={projectId}
          onCancel={() => setEditItem(null)}
          onSaved={() => {
            setSelectedId(undefined);
            setEditItem(null);
          }}
        />
      </div>
    );
  }

  return (
    <div className="flex gap-0">
      <div className="w-[50vw] min-w-0 flex-1 [overflow-anchor:none]">
        <div className="mx-6 mt-6">
          <p className="comet-body-s-accented">Agent configuration</p>
        </div>

        {selectedItem ? (
          <ConfigurationDetailView
            item={selectedItem}
            projectId={projectId}
            versions={allRows}
            onEdit={() => setEditItem(allRows[0])}
          />
        ) : (
          <div className="flex h-full items-center justify-center text-muted-slate">
            Select a version to view its configuration
          </div>
        )}
      </div>

      <div className="w-[25vw] shrink-0 pr-2">
        <p className="comet-body-s-accented ml-3 mt-6">Version history</p>

        <ConfigurationHistoryTimeline
          items={allRows}
          selectedIndex={selectedIndex}
          onSelect={(index) => setSelectedId(allRows[index]?.id ?? undefined)}
          hasNextPage={hasNextPage}
          isFetchingNextPage={isFetchingNextPage}
          onLoadMore={fetchNextPage}
        />
      </div>
    </div>
  );
};

export default ConfigurationTab;
