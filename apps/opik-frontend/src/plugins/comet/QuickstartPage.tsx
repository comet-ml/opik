import useUser from "./useUser";
import Quickstart from "@/components/pages/QuickstartPage/Quickstart";

const QuickstartPage = () => {
  const { data: user } = useUser();

  if (!user) return;

  return <Quickstart />;
};

export default QuickstartPage;
