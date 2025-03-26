import React from "react";
import { Book, Plus } from "lucide-react";
import noDataRulesImageUrl from "/images/no-data-rules.png";
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

type NoRulesPageProps = {
  openModal: () => void;
  height?: number;
  Wrapper: React.FC<NoDataWrapperProps>;
  className?: string;
};

const NoRulesPage: React.FC<NoRulesPageProps> = ({
  openModal,
  Wrapper,
  height,
  className,
}) => {
  return (
    <Wrapper
      title="Automate your scores"
      description="An automated rule is a predefined logic that scores LLM outputs in real-time based on set criteria, ensuring efficient and consistent performance assessment."
      imageUrl={noDataRulesImageUrl}
      height={height}
      className={className}
      buttons={
        <>
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl("/production/rules")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4"></Book>
              Read documentation
            </a>
          </Button>
          <Button onClick={openModal}>
            <Plus className="mr-2 size-4" />
            Create your first rule
          </Button>
        </>
      }
    ></Wrapper>
  );
};

export default NoRulesPage;
