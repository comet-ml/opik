import { SchemaType } from "../../Schema";
import { createIdentitySchemaCreator } from "../../utils/createIdentitySchemaCreator";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType";

export const string = createIdentitySchemaCreator<string>(
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
