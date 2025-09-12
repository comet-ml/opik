import { Button } from "@/components/ui/button";
import { Book, PenLine } from "lucide-react";
import React from "react";
import { buildDocsUrl } from "@/lib/utils";

const entityCopy = {
  thread: "threads",
  trace: "traces/LLM calls",
  experiment: "experiments",
  span: "traces/LLM calls",
};

type FeedbackScoreTableNoDataProps = {
  onAddHumanReview: () => void;
  entityType: "trace" | "thread" | "span" | "experiment";
};
const FeedbackScoreTableNoData: React.FC<FeedbackScoreTableNoDataProps> = ({
  onAddHumanReview,
  entityType,
}) => {
  const evaluationDocsLink = buildDocsUrl("/production/rules");
  return (
    <div className="flex min-h-48 flex-col items-center justify-center gap-2 bg-background p-6">
      <div>No feedback scores yet</div>
      <span className="max-w-[500px] whitespace-pre-wrap break-words text-center text-muted-slate">
        Use the SDK or Online evaluation rules to automatically score your{" "}
        {entityCopy[entityType]}, or manually annotate your{" "}
        {entityCopy[entityType]} with human review.
      </span>
      <div className="flex flex-wrap justify-center gap-2 pt-3">
        <Button variant="outline" size="sm" onClick={onAddHumanReview}>
          <PenLine className="mr-2 size-4" />
          Add human review
        </Button>
        <Button variant="secondary" size="sm" asChild>
          <a
            href={evaluationDocsLink}
            target="_blank"
            rel="noopener noreferrer"
          >
            <Book className="mr-2 size-4" />
            Learn about online evaluation
          </a>
        </Button>
      </div>
    </div>
  );
};

export default FeedbackScoreTableNoData;
