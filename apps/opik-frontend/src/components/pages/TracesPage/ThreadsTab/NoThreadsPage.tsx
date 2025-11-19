import React, { useState } from "react";
import { Book, GraduationCap } from "lucide-react";
import noDataThreadsImageUrl from "/images/no-data-threads.png";
import { Button } from "@/components/ui/button";
import { buildDocsUrl } from "@/lib/utils";
import QuickstartDialog from "@/components/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import NoDataPage from "@/components/shared/NoDataPage/NoDataPage";

const NoThreadsPage = () => {
  const [openQuickstart, setOpenQuickstart] = useState(false);

  return (
    <NoDataPage
      title="Log your first thread"
      description="Threads allow you to group traces together to help you evaluate your LLM model outputs in their specific context."
      imageUrl={noDataThreadsImageUrl}
      height={188}
      className="px-6"
      buttons={
        <>
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl("/tracing/log_chat_conversations")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4"></Book>
              Read documentation
            </a>
          </Button>
          <Button onClick={() => setOpenQuickstart(true)}>
            <GraduationCap className="mr-2 size-4" />
            Explore Quickstart guide
          </Button>
          <QuickstartDialog open={openQuickstart} setOpen={setOpenQuickstart} />
        </>
      }
    ></NoDataPage>
  );
};

export default NoThreadsPage;
