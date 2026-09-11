import QuickstartDialog from "@/v2/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import ProvideFeedbackDialog from "@/shared/SupportHub/FeedbackDialog/ProvideFeedbackDialog";
import { useLayoutDialog } from "@/hooks/useLayoutDialog";

const LayoutDialogs = () => {
  const { isOpen: isFeedbackOpen, setOpen: setFeedbackOpen } =
    useLayoutDialog("feedback");

  return (
    <>
      <QuickstartDialog />
      <ProvideFeedbackDialog open={isFeedbackOpen} setOpen={setFeedbackOpen} />
    </>
  );
};

export default LayoutDialogs;
