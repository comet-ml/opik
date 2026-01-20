import { type Schema, SchemaType } from "../../Schema.js";
import { createIdentitySchemaCreator } from "../../utils/createIdentitySchemaCreator.js";

export const never: () => Schema<never, never> = createIdentitySchemaCreator<never>(
    SchemaType.NEVER,
    (_value, { breadcrumbsPrefix = [] } = {}) => ({
        ok: false,
        errors: [
            {
                path: breadcrumbsPrefix,
                message: "Expected never",
            },
        ],
    }),
);
