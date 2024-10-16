import useUser from "./useUser";
import GetStarted from "@/components/pages/GetStartedPage/GetStarted";

const GetStartedPage = () => {
  const { data: user } = useUser();

  if (!user) return;
  return <GetStarted apiKey={user.apiKeys[0]} userName={user.userName} />;
};

export default GetStartedPage;
