import React from "react";
import { Book } from "lucide-react";
import noData from "/images/no-data-annotation-queue.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import { ANNOTATION_QUEUE_SCOPE } from "@/types/annotation-queues";

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
  openAddItemsDialog?: () => void;
  height?: number;
  Wrapper: React.FC<NoDataWrapperProps>;
  className?: string;
};

const NoQueueItemsPage: React.FC<NoQueueItemsPageProps> = ({
  queueScope,
  Wrapper,
  height,
  className,
}) => {
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
              href={buildDocsUrl("/production/annotation-queues")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4" />
              Read documentation
            </a>
          </Button>
        </>
      }
    />
  );
};

export default NoQueueItemsPage;
