import React from "react";
import useUser from "./useUser";
import CommentsViewerCore, {
  CommentsViewerCoreProps,
} from "@/components/pages-shared/traces/TraceDetailsPanel/CommentsViewer/CommentsViewerCore";

const CommentsViewer: React.FC<CommentsViewerCoreProps> = (props) => {
  const { data: user } = useUser();

  if (!user) return;

  return <CommentsViewerCore userName={user.userName} {...props} />;
};

export default CommentsViewer;
