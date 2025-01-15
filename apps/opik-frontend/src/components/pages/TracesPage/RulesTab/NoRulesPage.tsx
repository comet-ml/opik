import React from "react";
import { Book, Plus } from "lucide-react";
import automateYourScoresImageUrl from "/images/automate-your-scores.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";

type NoRulesPageProps = {
  openModal: () => void;
};

const NoRulesPage: React.FC<NoRulesPageProps> = ({ openModal }) => {
  return (
    <div className="min-w-[340px] py-6">
      <div className="flex flex-col items-center rounded-md border bg-white px-6 pb-6 pt-20">
        <h2 className="comet-title-m">Automate your scores</h2>
        <div className="comet-body-s max-w-[570px] px-4 pb-8 pt-4 text-center text-muted-slate">
          An automated rule is a predefined logic that scores LLM outputs in
          real-time based on set criteria, ensuring efficient and consistent
          performance assessment.
        </div>
        <img
          className="max-h-[400px] object-cover"
          src={automateYourScoresImageUrl}
          alt="image automate your scores"
        />
        <div className="flex flex-wrap justify-center gap-2 pt-8">
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
        </div>
      </div>
    </div>
  );
};

export default NoRulesPage;
