import { Schema, SchemaType } from "../../Schema";
import { createIdentitySchemaCreator } from "../../utils/createIdentitySchemaCreator";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType";

export function booleanLiteral<V extends boolean>(literal: V): Schema<V, V> {
    const schemaCreator = createIdentitySchemaCreator(
        SchemaType.BOOLEAN_LITERAL,
        (value, { breadcrumbsPrefix = [] } = {}) => {
            if (value === literal) {
                return {
                    ok: true,
                    value: literal,
                };
            } else {
                return {
                    ok: false,
                    errors: [
                        {
                            path: breadcrumbsPrefix,
                            message: getErrorMessageForIncorrectType(value, `${literal.toString()}`),
                        },
                    ],
                };
            }
        },
    );

    return schemaCreator();
}
