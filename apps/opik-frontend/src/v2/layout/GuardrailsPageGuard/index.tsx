import useGuardrailsEnabled from "@/api/guardrail-policies/useGuardrailsEnabled";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import { Button } from "@/ui/button";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const GuardrailsPageGuard = () => {
  const { isEnabled, isPending, isError, refetch } = useGuardrailsEnabled();

  if (isPending) return <Loader />;

  if (isError) {
    return (
      <NoData
        title="Guardrails could not be checked"
        message="Something went wrong while checking whether guardrails are enabled for this workspace."
      >
        <Button className="mt-5" onClick={() => refetch()}>
          Try again
        </Button>
      </NoData>
    );
  }

  return (
    <NoAccessPageGuard
      canViewPage={isEnabled}
      message="Guardrails are not enabled for this workspace."
    />
  );
};

export default GuardrailsPageGuard;
