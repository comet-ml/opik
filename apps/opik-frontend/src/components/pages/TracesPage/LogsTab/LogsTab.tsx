import React from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import TracesSpansTab from "@/components/pages/TracesPage/TracesSpansTab/TracesSpansTab";
import ThreadsTab from "@/components/pages/TracesPage/ThreadsTab/ThreadsTab";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import { LOGS_TYPE, TRACE_DATA_TYPE } from "@/constants/traces";

const DEFAULT_TYPE = LOGS_TYPE.traces; // TODO: Prefer thread if threadCount > 0, otherwise traces

type LogsTabProps = {
  projectId: string;
  projectName: string;
};

const LogsTab: React.FC<LogsTabProps> = ({ projectId, projectName }) => {
  const [type = DEFAULT_TYPE, setType] = useQueryParam("type", StringParam, {
    updateType: "replaceIn",
  });

  const renderContent = () => {
    switch (type) {
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
      default:
        return (
          <TracesSpansTab
            key="traces-default"
            type={TRACE_DATA_TYPE.traces}
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
          value={type as string}
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
