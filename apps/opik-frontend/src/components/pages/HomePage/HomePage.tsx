import useAppStore from "@/store/AppStore";
import { Navigate } from "@tanstack/react-router";

const HomePage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return <Navigate to="/$workspaceName/projects" params={{ workspaceName }} />;
};

export default HomePage;
