import React, { useState } from "react";
import { FlaskConical, InspectionPanel, MousePointer } from "lucide-react";
import useAppStore from "@/store/AppStore";
import SideDialog from "@/components/shared/SideDialog/SideDialog";
import FrameworkIntegrations from "@/components/pages-shared/onboarding/FrameworkIntegrations/FrameworkIntegrations";
import AddExperimentDialog from "../ExperimentsShared/AddExperimentDialog";
import { Link } from "@tanstack/react-router";

const GetStartedSection = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [isNewExperimentDialogOpened, setIsNewExperimentDialogOpened] =
    useState<boolean>(false);
  const [isLogTraceDialogOpened, setIsLogTraceDialogOpened] = useState(false);

  const openNewExperimentDialog = () => setIsNewExperimentDialogOpened(true);
  const openLogTraceDialog = () => setIsLogTraceDialogOpened(true);

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
        <div
          onClick={openLogTraceDialog}
          className="flex w-full max-w-[300px] cursor-pointer items-center gap-3 rounded-md border bg-white p-4 transition-shadow hover:shadow-md"
        >
          <div className="flex size-[24px] items-center justify-center rounded bg-[#DAFBF0] ">
            <InspectionPanel className="size-3.5 text-[#295747]" />
          </div>
          <div className="comet-body-s">Log a trace</div>
        </div>
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
      </div>

      <SideDialog
        open={isLogTraceDialogOpened}
        setOpen={setIsLogTraceDialogOpened}
      >
        <div className="flex w-full min-w-fit flex-col pb-12">
          <div className="pb-8">
            <h1 className="comet-title-l text-center">Log a trace</h1>
            <div className="comet-body-s m-auto mt-4 w-[468px] self-center text-center text-muted-slate">
              Select a framework and follow the instructions to integrate Comet
              with your code, or explore our ready-to-run examples on the right
            </div>
          </div>
          <FrameworkIntegrations />
        </div>
      </SideDialog>

      <AddExperimentDialog
        open={isNewExperimentDialogOpened}
        setOpen={setIsNewExperimentDialogOpened}
      />
    </div>
  );
};

export default GetStartedSection;
