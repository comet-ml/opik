/**
 * This file was auto-generated by Fern from our API Definition.
 */
import * as OpikApi from "../index";
export declare type FeedbackPublic = OpikApi.FeedbackPublic.Numerical | OpikApi.FeedbackPublic.Categorical;
export declare namespace FeedbackPublic {
    interface Numerical extends OpikApi.NumericalFeedbackDefinitionPublic, _Base {
        type: "numerical";
    }
    interface Categorical extends OpikApi.CategoricalFeedbackDefinitionPublic, _Base {
        type: "categorical";
    }
    interface _Base {
        id?: string;
        name: string;
        createdAt?: Date;
        createdBy?: string;
        lastUpdatedAt?: Date;
        lastUpdatedBy?: string;
    }
}