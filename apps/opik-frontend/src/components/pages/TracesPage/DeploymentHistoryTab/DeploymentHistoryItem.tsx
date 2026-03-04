import React from "react";

import { ConfigHistoryItem } from "@/types/agent-configs";
import { formatDate } from "@/lib/date";
import ColoredTag from "@/components/shared/ColoredTag/ColoredTag";

type DeploymentHistoryItemProps = {
  item: ConfigHistoryItem;
  version: number;
};

const DeploymentHistoryItem: React.FC<DeploymentHistoryItemProps> = ({
  item,
  version,
}) => {
  return (
    <div className="overflow-y-auto px-6 py-4">
      <div className="mb-4 flex items-center gap-2">
        <h2 className="comet-title-m">v{version}</h2>
        {item.tags.map((tag) => (
          <ColoredTag key={tag} label={tag} />
        ))}
      </div>
      <p className="comet-body-s text-light-slate">{item.description}</p>
      <p className="comet-body-xs mt-2 text-muted-slate">
        {item.created_by} &middot; {formatDate(item.created_at)}
      </p>
    </div>
  );
};

export default DeploymentHistoryItem;
