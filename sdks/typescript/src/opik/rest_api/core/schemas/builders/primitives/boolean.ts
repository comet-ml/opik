import { SchemaType } from "../../Schema";
import { createIdentitySchemaCreator } from "../../utils/createIdentitySchemaCreator";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType";

export const boolean = createIdentitySchemaCreator<boolean>(
    SchemaType.BOOLEAN,
    (value, { breadcrumbsPrefix = [] } = {}) => {
        if (typeof value === "boolean") {
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
                        message: getErrorMessageForIncorrectType(value, "boolean"),
                    },
                ],
            };
        }
    }
);
