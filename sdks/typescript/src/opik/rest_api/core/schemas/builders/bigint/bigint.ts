import { BaseSchema, Schema, SchemaType } from "../../Schema";
import { getErrorMessageForIncorrectType } from "../../utils/getErrorMessageForIncorrectType";
import { maybeSkipValidation } from "../../utils/maybeSkipValidation";
import { getSchemaUtils } from "../schema-utils";

export function bigint(): Schema<bigint | number, bigint> {
    const baseSchema: BaseSchema<bigint | number, bigint> = {
        parse: (raw, { breadcrumbsPrefix = [] } = {}) => {
            if (typeof raw === "bigint") {
                return {
                    ok: true,
                    value: raw,
                };
            }
            if (typeof raw === "number") {
                return {
                    ok: true,
                    value: BigInt(raw),
                };
            }
            return {
                ok: false,
                errors: [
                    {
                        path: breadcrumbsPrefix,
                        message: getErrorMessageForIncorrectType(raw, "bigint | number"),
                    },
                ],
            };
        },
        json: (bigint, { breadcrumbsPrefix = [] } = {}) => {
            if (typeof bigint !== "bigint") {
                return {
                    ok: false,
                    errors: [
                        {
                            path: breadcrumbsPrefix,
                            message: getErrorMessageForIncorrectType(bigint, "bigint"),
                        },
                    ],
                };
            }
            return {
                ok: true,
                value: bigint,
            };
        },
        getType: () => SchemaType.BIGINT,
    };

    return {
        ...maybeSkipValidation(baseSchema),
        ...getSchemaUtils(baseSchema),
    };
}
