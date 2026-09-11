import React from "react";
import TracesSpansTab from "@/v2/pages/LogsPage/TracesSpansTab/TracesSpansTab";
import ThreadsTab from "@/v2/pages/LogsPage/ThreadsTab/ThreadsTab";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";
import { ProjectDateRangeConfig } from "@/v2/pages-shared/traces/resolveProjectDateRangeConfig";

type LogsTabProps = {
  projectId: string;
  projectName: string;
  logsType: LOGS_TYPE;
  onLogsTypeChange: (type: LOGS_TYPE) => void;
  dateRangeConfig: ProjectDateRangeConfig;
};

const LogsTab: React.FC<LogsTabProps> = ({
  projectId,
  projectName,
  logsType,
  onLogsTypeChange,
  dateRangeConfig,
}) => {
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
          />
        );
    }
  };

  return renderContent();
};

export default LogsTab;
