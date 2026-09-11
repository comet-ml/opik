import { useEffect } from "react";
import { useNavigate } from "@tanstack/react-router";
import { useActiveWorkspaceName } from "@/store/AppStore";
import Loader from "@/shared/Loader/Loader";

const HomePage = () => {
  const workspaceName = useActiveWorkspaceName();
  const navigate = useNavigate();

  useEffect(() => {
    navigate({
      to: "/$workspaceName/projects",
      params: { workspaceName },
      replace: true,
    });
  }, [workspaceName, navigate]);

  return <Loader />;
};

export default HomePage;
