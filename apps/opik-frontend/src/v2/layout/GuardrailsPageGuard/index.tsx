import useGuardrailsEnabled from "@/api/guardrail-policies/useGuardrailsEnabled";
import Loader from "@/shared/Loader/Loader";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const GuardrailsPageGuard = () => {
  const { isEnabled, isPending } = useGuardrailsEnabled();

  if (isPending) return <Loader />;

  return (
    <NoAccessPageGuard
      canViewPage={isEnabled}
      message="Guardrails are not enabled for this workspace."
    />
  );
};

export default GuardrailsPageGuard;
