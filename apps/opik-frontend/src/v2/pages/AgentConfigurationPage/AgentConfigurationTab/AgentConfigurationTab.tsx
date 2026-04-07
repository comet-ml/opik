import React, { useMemo, useState } from "react";
import { Book, Settings2 } from "lucide-react";
import { StringParam, useQueryParam } from "use-query-params";

import Loader from "@/shared/Loader/Loader";
import { buildDocsUrl } from "@/lib/utils";
import useConfigHistoryListInfinite from "@/api/agent-configs/useConfigHistoryListInfinite";
import { ConfigHistoryItem } from "@/types/agent-configs";
import AgentConfigurationHistoryTimeline from "./AgentConfigurationHistoryTimeline";
import AgentConfigurationDetailView from "./AgentConfigurationDetailView";
import AgentConfigurationEditView from "@/v2/pages-shared/agent-configuration/AgentConfigurationEditView";

type AgentConfigurationTabProps = {
  projectId: string;
};

const AgentConfigurationTab: React.FC<AgentConfigurationTabProps> = ({
  projectId,
}) => {
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
      <div className="w-full p-4">
        <AgentConfigurationEditView
          item={editItem}
          projectId={projectId}
          onSaved={() => {
            setSelectedId(undefined);
            setEditItem(null);
          }}
        />
      </div>
    );
  }

  return (
    <div>
      <h1 className="comet-title-xs px-6 pt-4">Agent configuration</h1>
      <div className="flex gap-0">
        <div className="w-[50vw] min-w-0 flex-1 [overflow-anchor:none]">
          {selectedItem ? (
            <AgentConfigurationDetailView
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
          <AgentConfigurationHistoryTimeline
            items={allRows}
            selectedIndex={selectedIndex}
            onSelect={(index) => setSelectedId(allRows[index]?.id ?? undefined)}
            hasNextPage={hasNextPage}
            isFetchingNextPage={isFetchingNextPage}
            onLoadMore={fetchNextPage}
          />
        </div>
      </div>
    </div>
  );
};

export default AgentConfigurationTab;
