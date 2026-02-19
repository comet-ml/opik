import React from "react";
import TracesSpansTab from "@/components/pages/TracesPage/TracesSpansTab/TracesSpansTab";
import ThreadsTab from "@/components/pages/TracesPage/ThreadsTab/ThreadsTab";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";

type LogsTabProps = {
  projectId: string;
  projectName: string;
  logsType: LOGS_TYPE;
  onLogsTypeChange: (type: LOGS_TYPE) => void;
};

const LogsTab: React.FC<LogsTabProps> = ({
  projectId,
  projectName,
  logsType,
  onLogsTypeChange,
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
          />
        );
    }
  };

  return renderContent();
};

export default LogsTab;
