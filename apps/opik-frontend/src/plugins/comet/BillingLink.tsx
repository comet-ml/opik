import { useActiveWorkspaceName } from "@/store/AppStore";
import useWorkspace from "@/plugins/comet/useWorkspace";
import { buildUrl } from "@/plugins/comet/utils";

const BillingLink = () => {
  const activeWorkspaceName = useActiveWorkspaceName();
  const workspace = useWorkspace(activeWorkspaceName);

  if (!workspace?.organizationId) return null;

  const href = buildUrl(
    `organizations/${workspace.organizationId}/ollie-credits`,
    activeWorkspaceName,
  );

  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="underline underline-offset-4 hover:text-primary"
    >
      View billing
    </a>
  );
};

export default BillingLink;
