import React from "react";
import { Book, Plus } from "lucide-react";
import noDataRulesImageUrl from "/images/no-data-rules.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import NoDataTab from "@/components/pages/TracesPage/NoDataTab";

type NoRulesPageProps = {
  openModal: () => void;
};

const NoRulesPage: React.FC<NoRulesPageProps> = ({ openModal }) => {
  return (
    <NoDataTab
      title="Automate your scores"
      description="An automated rule is a predefined logic that scores LLM outputs in real-time based on set criteria, ensuring efficient and consistent performance assessment."
      imageUrl={noDataRulesImageUrl}
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
    ></NoDataTab>
  );
};

export default NoRulesPage;
