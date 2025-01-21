import React, { useState } from "react";
import { FlaskConical, InspectionPanel, MousePointer } from "lucide-react";
import useAppStore from "@/store/AppStore";
import { buildDocsUrl } from "@/lib/utils";
import AddExperimentDialog from "../ExperimentsShared/AddExperimentDialog";
import { Link } from "@tanstack/react-router";

const GetStartedSection = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isNewExperimentDialogOpened, setIsNewExperimentDialogOpened] =
    useState<boolean>(false);

  const openNewExperimentDialog = () => setIsNewExperimentDialogOpened(true);

  return (
    <div>
      <div className="flex items-center justify-between gap-8 pb-4 pt-2">
        <div className="flex items-center gap-2">
          <h2 className="comet-body-accented truncate break-words">
            Get started
          </h2>
        </div>
      </div>
      <div className="flex gap-x-4">
        <a
          href={buildDocsUrl("/tracing/log_traces")}
          target="_blank"
          rel="noreferrer"
          className="flex w-full max-w-[300px] cursor-pointer items-center gap-3 rounded-md border bg-white p-4 transition-shadow hover:shadow-md"
        >
          <div className="flex size-[24px] items-center justify-center rounded bg-[#DAFBF0] ">
            <InspectionPanel className="size-3.5 text-[#295747]" />
          </div>
          <div className="comet-body-s">Log a trace</div>
        </a>
        <div
          onClick={openNewExperimentDialog}
          className="flex w-full max-w-[300px] cursor-pointer items-center gap-3 rounded-md border bg-white p-4 transition-shadow hover:shadow-md"
        >
          <div className="flex size-[24px] items-center justify-center rounded bg-[#FDE2F6] ">
            <MousePointer className="size-3.5 text-[#72275F]" />
          </div>
          <div className="comet-body-s">Run an experiment</div>
        </div>
        <Link
          className="flex w-full max-w-[300px] cursor-pointer items-center gap-3 rounded-md border bg-white p-4 transition-shadow hover:shadow-md"
          to={"/$workspaceName/playground"}
          params={{ workspaceName }}
        >
          <div className="flex size-[24px] items-center justify-center rounded bg-[#EFE2FD] ">
            <FlaskConical className="size-3.5 text-[#491B7E]" />
          </div>
          <div className="comet-body-s">Try out playground</div>
        </Link>

        <AddExperimentDialog
          open={isNewExperimentDialogOpened}
          setOpen={setIsNewExperimentDialogOpened}
        />
      </div>
    </div>
  );
};

export default GetStartedSection;
