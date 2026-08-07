import { Coins } from "lucide-react";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useUser from "@/plugins/comet/useUser";
import { buildUrl } from "@/plugins/comet/utils";

export type BillingLinkProps = {
  label?: string;
  variant?: "inline" | "action";
};

const BillingLink = ({
  label = "View billing",
  variant = "inline",
}: BillingLinkProps) => {
  const activeWorkspaceName = useActiveWorkspaceName();
  const { data: user } = useUser();
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const workspace = allWorkspaces?.find(
    (w) => w.workspaceName === activeWorkspaceName,
  );

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
      className={
        variant === "action"
          ? "inline-flex items-center gap-1 text-xs font-normal hover:text-primary-hover"
          : "underline underline-offset-4 hover:text-primary"
      }
    >
      {variant === "action" && <Coins className="size-3" />}
      {label}
    </a>
  );
};

export default BillingLink;
