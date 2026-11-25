import React from "react";
import { getTimeFromNow } from "@/lib/date";
import { HistoryVersion } from "@/lib/model-prices";

type HistoryPanelProps = {
  history: HistoryVersion[];
};

const HistoryPanel: React.FC<HistoryPanelProps> = ({ history }) => (
  <div className="mb-4 rounded-md border p-4" data-testid="model-price-history">
    <h3 className="comet-body-accented mb-2">History</h3>
    <ul className="flex flex-col gap-2">
      {history.map((entry) => (
        <li key={entry.version} className="comet-body-s">
          <span className="comet-body-s-accented">Version {entry.version}</span>
          <span className="text-muted-slate">
            {" "}
            · {entry.createdBy} · {getTimeFromNow(entry.createdAt)}
          </span>
          <ul className="list-disc pl-5 text-muted-slate">
            {entry.lines.map((line, index) => (
              <li key={index}>{line}</li>
            ))}
          </ul>
        </li>
      ))}
    </ul>
    <p className="comet-body-xs mt-3 text-light-slate">
      Every save keeps each row before and after, so any change can be traced.
    </p>
  </div>
);

export default HistoryPanel;
