import GetStarted from "@/components/pages/GetStartedPage/GetStarted";
import useUser from "./useUser";

const GetStartedPage = () => {
  const { data: user } = useUser();

  if (!user) return;

  return <GetStarted />;
};

export default GetStartedPage;
