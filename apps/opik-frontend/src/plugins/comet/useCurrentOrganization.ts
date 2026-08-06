import useOrganizations from "./useOrganizations";
import useUser from "./useUser";
import useWorkspace from "./useWorkspace";

const useCurrentOrganization = () => {
  const { data: user } = useUser();

  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const currentWorkspace = useWorkspace();

  const currentOrganization = organizations?.find((org) => {
    return org.id === currentWorkspace?.organizationId;
  });

  return currentOrganization;
};

export default useCurrentOrganization;
