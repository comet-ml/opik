import usePluginsStore from "@/store/PluginsStore";
import CommentsViewerCore, {
  CommentsViewerCoreProps,
} from "./CommentsViewerCore";

export type CommentsViewerProps = Omit<CommentsViewerCoreProps, "user">;
const CommentsViewer: React.FC<CommentsViewerProps> = (props) => {
  const CommentsViewer = usePluginsStore((state) => state.CommentsViewer);

  if (CommentsViewer) {
    return <CommentsViewer {...props} />;
  }

  return <CommentsViewerCore {...props} />;
};

export default CommentsViewer;
