import first from "lodash/first";
import get from "lodash/get";
import { Trace, Span, Thread } from "@/types/traces";
import { COLUMN_FEEDBACK_SCORES_ID } from "@/types/shared";

export async function mapRowDataForExport(
  rows: Array<Trace | Span | Thread>,
  columnsToExport: string[],
): Promise<Array<Record<string, unknown>>> {
  return rows.map((row) => {
    return columnsToExport.reduce<Record<string, unknown>>((acc, column) => {
      // we need split by dot to parse feedback_scores into correct structure
      const keys = column.split(".");
      const keyPrefix = first(keys) as string;

      if (keyPrefix === COLUMN_FEEDBACK_SCORES_ID) {
        const scoreName = column.replace(`${COLUMN_FEEDBACK_SCORES_ID}.`, "");
        const scoreObject = row.feedback_scores?.find(
          (f) => f.name === scoreName,
        );
        acc[column] = get(scoreObject, "value", "-");

        if (scoreObject && scoreObject.reason) {
          acc[`${column}_reason`] = scoreObject.reason;
        }
      } else {
        acc[column] = get(row, keys, "");
      }

      return acc;
    }, {});
  });
}
