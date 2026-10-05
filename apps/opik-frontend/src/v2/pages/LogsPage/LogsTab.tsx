import React from "react";
import TracesSpansTab from "@/v2/pages/LogsPage/TracesSpansTab/TracesSpansTab";
import ThreadsTab from "@/v2/pages/LogsPage/ThreadsTab/ThreadsTab";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";
import { ProjectDateRangeConfig } from "@/v2/pages-shared/traces/resolveProjectDateRangeConfig";
import { IntervalWindow } from "@/v2/pages-shared/traces/MetricDateRangeSelect";
import useLogsIntervalWindow from "@/v2/pages/LogsPage/useLogsIntervalWindow";

type LogsTabProps = {
  projectId: string;
  projectName: string;
  logsType: LOGS_TYPE;
  onLogsTypeChange: (type: LOGS_TYPE) => void;
  dateRangeConfig: ProjectDateRangeConfig;
  intervalWindow?: IntervalWindow;
};

const LogsTab: React.FC<LogsTabProps> = ({
  projectId,
  projectName,
  logsType,
  onLogsTypeChange,
  dateRangeConfig,
  intervalWindow,
}) => {
  const ownIntervalWindow = useLogsIntervalWindow(
    dateRangeConfig,
    !intervalWindow,
  );
  const tabIntervalWindow = intervalWindow ?? ownIntervalWindow;

  const renderContent = () => {
    switch (logsType) {
      case LOGS_TYPE.threads:
        return (
          <ThreadsTab
            projectId={projectId}
            projectName={projectName}
            logsType={logsType}
            onLogsTypeChange={onLogsTypeChange}
            dateRangeConfig={dateRangeConfig}
            intervalWindow={tabIntervalWindow}
          />
        );
      case LOGS_TYPE.traces:
        return (
          <TracesSpansTab
            key="traces"
            type={TRACE_DATA_TYPE.traces}
            projectId={projectId}
            projectName={projectName}
            logsType={logsType}
            onLogsTypeChange={onLogsTypeChange}
            dateRangeConfig={dateRangeConfig}
            intervalWindow={tabIntervalWindow}
          />
        );
      case LOGS_TYPE.spans:
        return (
          <TracesSpansTab
            key="spans"
            type={TRACE_DATA_TYPE.spans}
            projectId={projectId}
            projectName={projectName}
            logsType={logsType}
            onLogsTypeChange={onLogsTypeChange}
            dateRangeConfig={dateRangeConfig}
            intervalWindow={tabIntervalWindow}
          />
        );
    }
  };

  return renderContent();
};

export default LogsTab;
