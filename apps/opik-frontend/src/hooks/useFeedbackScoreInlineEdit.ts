import useThreadFeedbackScoreDeleteMutation from "@/api/traces/useThreadFeedbackScoreDeleteMutation";
import useThreadFeedbackScoreSetMutation from "@/api/traces/useThreadFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import { USER_FEEDBACK_NAME } from "@/constants/shared";
import { useLoggedInUserNameOrOpenSourceDefaultUser } from "@/store/AppStore";
import { TraceFeedbackScore } from "@/types/traces";
import { useCallback } from "react";
import { findValueByAuthor } from "@/lib/feedback-scores";

interface UseFeedbackScoreInlineEditProps {
  id: string;
  feedbackScore?: TraceFeedbackScore;
  projectId?: string;
  projectName?: string;
  isThread?: boolean;
  isSpan?: boolean;
}

const useFeedbackScoreInlineEdit = ({
  id,
  feedbackScore,
  projectId,
  projectName,
  isThread = false,
  isSpan = false,
}: UseFeedbackScoreInlineEditProps) => {
  const currentUserName = useLoggedInUserNameOrOpenSourceDefaultUser();

  const { mutate: deleteTraceFeedbackScore } =
    useTraceFeedbackScoreDeleteMutation();
  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();

  const { mutate: deleteThreadFeedbackScore } =
    useThreadFeedbackScoreDeleteMutation();
  const { mutate: setThreadFeedbackScore } =
    useThreadFeedbackScoreSetMutation();

  const handleValueChange = useCallback(
    (categoryName: string, value: number) => {
      const userValue = findValueByAuthor(
        feedbackScore?.value_by_author,
        currentUserName,
      );
      const isSameValue = userValue?.value === value;

      if (isThread) {
        // Handle Thread feedback score
        if (!projectId || !projectName) {
          console.error(
            "projectId and projectName are required for thread feedback scores",
          );
          return;
        }

        if (!isSameValue) {
          setThreadFeedbackScore({
            threadId: id,
            projectId,
            projectName,
            scores: [
              {
                name: USER_FEEDBACK_NAME,
                categoryName,
                value,
              },
            ],
          });
        } else {
          deleteThreadFeedbackScore({
            threadId: id,
            projectId,
            projectName,
            names: [USER_FEEDBACK_NAME],
          });
        }
      } else {
        // Handle Trace/Span feedback score
        if (!isSameValue) {
          setTraceFeedbackScore({
            traceId: id,
            spanId: isSpan ? id : undefined,
            name: USER_FEEDBACK_NAME,
            categoryName,
            value,
          });
        } else {
          deleteTraceFeedbackScore({
            traceId: id,
            spanId: isSpan ? id : undefined,
            name: USER_FEEDBACK_NAME,
          });
        }
      }
    },
    [
      feedbackScore?.value_by_author,
      currentUserName,
      isThread,
      projectId,
      projectName,
      setThreadFeedbackScore,
      id,
      deleteThreadFeedbackScore,
      setTraceFeedbackScore,
      isSpan,
      deleteTraceFeedbackScore,
    ],
  );

  return { handleValueChange };
};

export default useFeedbackScoreInlineEdit;
