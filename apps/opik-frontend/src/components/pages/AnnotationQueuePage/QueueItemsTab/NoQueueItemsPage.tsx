import React from "react";
import { Book, ExternalLink } from "lucide-react";
import { Link } from "@tanstack/react-router";
import noData from "/images/no-data-annotation-queue.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import {
  ANNOTATION_QUEUE_SCOPE,
  AnnotationQueue,
} from "@/types/annotation-queues";
import { useActiveWorkspaceName } from "@/store/AppStore";
import { LOGS_TYPE, PROJECT_TAB } from "@/constants/traces";

type NoDataWrapperProps = {
  title: string;
  description: string;
  imageUrl: string;
  buttons: React.ReactNode;
  className?: string;
  height?: number;
};

type NoQueueItemsPageProps = {
  queueScope: ANNOTATION_QUEUE_SCOPE;
  annotationQueue?: AnnotationQueue;
  openAddItemsDialog?: () => void;
  height?: number;
  Wrapper: React.FC<NoDataWrapperProps>;
  className?: string;
};

const NoQueueItemsPage: React.FC<NoQueueItemsPageProps> = ({
  queueScope,
  annotationQueue,
  Wrapper,
  height,
  className,
}) => {
  const workspaceName = useActiveWorkspaceName();
  const isTraceQueue = queueScope === ANNOTATION_QUEUE_SCOPE.TRACE;
  const itemType = isTraceQueue ? "traces" : "threads";

  return (
    <Wrapper
      title={`Add ${itemType} to your queue`}
      description={`Your annotation queue is empty. Add ${itemType} from your project to start the annotation process and gather valuable feedback.`}
      imageUrl={noData}
      height={height}
      className={className}
      buttons={
        <>
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl(
                "/evaluation/annotation_queues",
                "#adding-content-to-your-queue",
              )}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4" />
              Read documentation
            </a>
          </Button>
          {annotationQueue?.project_id && (
            <Button variant="outline" asChild>
              <Link
                to="/$workspaceName/projects/$projectId/traces"
                params={{
                  workspaceName,
                  projectId: annotationQueue.project_id,
                }}
                search={{
                  tab: PROJECT_TAB.logs,
                  logsType: isTraceQueue ? LOGS_TYPE.traces : LOGS_TYPE.threads,
                }}
              >
                <ExternalLink className="mr-2 size-4" />
                {isTraceQueue ? "Go to traces" : "Go to threads"}
              </Link>
            </Button>
          )}
        </>
      }
    />
  );
};

export default NoQueueItemsPage;
