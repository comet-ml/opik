import GetStarted from "@/components/pages/GetStartedPage/GetStarted";
import useUser from "./useUser";

const GetStartedPage = () => {
  const { data: user } = useUser();

  if (!user) return;

  return (
    <GetStarted
      apiKey={user.apiKeys[0]}
      showColabLinks={!user?.sagemakerRestrictions}
    />
  );
};

export default GetStartedPage;
