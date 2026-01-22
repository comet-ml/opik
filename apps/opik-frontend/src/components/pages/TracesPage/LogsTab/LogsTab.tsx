import React from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import TracesSpansTab from "@/components/pages/TracesPage/TracesSpansTab/TracesSpansTab";
import ThreadsTab from "@/components/pages/TracesPage/ThreadsTab/ThreadsTab";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";

type LogsTabProps = {
  projectId: string;
  projectName: string;
  defaultLogsType: LOGS_TYPE;
};

const LogsTab: React.FC<LogsTabProps> = ({
  projectId,
  projectName,
  defaultLogsType,
}) => {
  const [type = defaultLogsType, setType] = useQueryParam("type", StringParam, {
    updateType: "replaceIn",
  });

  // Normalize type to valid LOGS_TYPE, fallback to defaultLogsType
  const validType = Object.values(LOGS_TYPE).includes(type as LOGS_TYPE)
    ? (type as LOGS_TYPE)
    : defaultLogsType;

  const renderContent = () => {
    switch (validType) {
      case LOGS_TYPE.threads:
        return <ThreadsTab projectId={projectId} projectName={projectName} />;
      case LOGS_TYPE.traces:
        return (
          <TracesSpansTab
            key="traces"
            type={TRACE_DATA_TYPE.traces}
            projectId={projectId}
            projectName={projectName}
          />
        );
      case LOGS_TYPE.spans:
        return (
          <TracesSpansTab
            key="spans"
            type={TRACE_DATA_TYPE.spans}
            projectId={projectId}
            projectName={projectName}
          />
        );
    }
  };

  return (
    <>
      <PageBodyStickyContainer
        className="mb-4 flex items-center"
        direction="horizontal"
        limitWidth
      >
        <ToggleGroup
          type="single"
          value={validType}
          onValueChange={(val) => val && setType(val as LOGS_TYPE)}
          variant="ghost"
          className="w-fit"
        >
          <ToggleGroupItem value={LOGS_TYPE.threads} size="sm">
            Threads
          </ToggleGroupItem>
          <ToggleGroupItem value={LOGS_TYPE.traces} size="sm">
            Traces
          </ToggleGroupItem>
          <ToggleGroupItem value={LOGS_TYPE.spans} size="sm">
            Spans
          </ToggleGroupItem>
        </ToggleGroup>
      </PageBodyStickyContainer>
      {renderContent()}
    </>
  );
};

export default LogsTab;
