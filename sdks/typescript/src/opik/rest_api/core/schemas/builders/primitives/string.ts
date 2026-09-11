import { type Schema, SchemaType } from "../../Schema.js";
import { createIdentitySchemaCreator } from "../../utils/createIdentitySchemaCreator.js";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType.js";

export const string: () => Schema<string, string> = createIdentitySchemaCreator<string>(
    SchemaType.STRING,
    (value, { breadcrumbsPrefix = [] } = {}) => {
        if (typeof value === "string") {
            return {
                ok: true,
                value,
            };
        } else {
            return {
                ok: false,
                errors: [
                    {
                        path: breadcrumbsPrefix,
                        message: getErrorMessageForIncorrectType(value, "string"),
                    },
                ],
            };
        }
    },
);
