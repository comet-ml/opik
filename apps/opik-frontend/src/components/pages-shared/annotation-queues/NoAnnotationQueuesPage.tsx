import React from "react";
import { Book, Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import noDataQueuesImageUrl from "/images/no-data-annotation-queues.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";

type NoDataWrapperProps = {
  title: string;
  description: string;
  imageUrl: string;
  buttons: React.ReactNode;
  className?: string;
  height?: number;
};

type NoAnnotationQueuesPageProps = {
  openModal: () => void;
  height?: number;
  Wrapper: React.FC<NoDataWrapperProps>;
  className?: string;
};

const NoAnnotationQueuesPage: React.FC<NoAnnotationQueuesPageProps> = ({
  openModal,
  Wrapper,
  height,
  className,
}) => {
  const { t } = useTranslation();
  
  return (
    <Wrapper
      title={t("annotationQueues.emptyState.title")}
      description={t("annotationQueues.emptyState.description")}
      imageUrl={noDataQueuesImageUrl}
      height={height}
      className={className}
      buttons={
        <>
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl("/evaluation/annotation_queues")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4"></Book>
              {t("annotationQueues.emptyState.readDocumentation")}
            </a>
          </Button>
          <Button onClick={openModal}>
            <Plus className="mr-2 size-4" />
            {t("annotationQueues.emptyState.createFirstQueue")}
          </Button>
        </>
      }
    ></Wrapper>
  );
};

export default NoAnnotationQueuesPage;
