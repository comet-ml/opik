"""
Type annotations for bedrock-runtime service type definitions.

[Documentation](https://youtype.github.io/boto3_stubs_docs/mypy_boto3_bedrock_runtime/type_defs/)

Copyright 2025 Vlad Emelianov

Usage::

    ```python
    from mypy_boto3_bedrock_runtime.type_defs import GuardrailOutputContentTypeDef

    data: GuardrailOutputContentTypeDef = ...
    ```
"""

from __future__ import annotations

import sys
from datetime import datetime
from typing import IO, Any, Union

from botocore.eventstream import EventStream
from botocore.response import StreamingBody

from .literals import (
    AsyncInvokeStatusType,
    ConversationRoleType,
    DocumentFormatType,
    GuardrailActionType,
    GuardrailContentFilterConfidenceType,
    GuardrailContentFilterStrengthType,
    GuardrailContentFilterTypeType,
    GuardrailContentPolicyActionType,
    GuardrailContentQualifierType,
    GuardrailContentSourceType,
    GuardrailContextualGroundingFilterTypeType,
    GuardrailContextualGroundingPolicyActionType,
    GuardrailConverseContentQualifierType,
    GuardrailConverseImageFormatType,
    GuardrailImageFormatType,
    GuardrailOutputScopeType,
    GuardrailPiiEntityTypeType,
    GuardrailSensitiveInformationPolicyActionType,
    GuardrailStreamProcessingModeType,
    GuardrailTopicPolicyActionType,
    GuardrailTraceType,
    GuardrailWordPolicyActionType,
    ImageFormatType,
    PerformanceConfigLatencyType,
    SortOrderType,
    StopReasonType,
    ToolResultStatusType,
    TraceType,
    VideoFormatType,
)

if sys.version_info >= (3, 9):
    from builtins import dict as Dict
    from builtins import list as List
    from collections.abc import Mapping, Sequence
else:
    from typing import Dict, List, Mapping, Sequence
if sys.version_info >= (3, 12):
    from typing import Literal, NotRequired, TypedDict
else:
    from typing_extensions import Literal, NotRequired, TypedDict

__all__ = (
    "ApplyGuardrailRequestTypeDef",
    "ApplyGuardrailResponseTypeDef",
    "AsyncInvokeOutputDataConfigTypeDef",
    "AsyncInvokeS3OutputDataConfigTypeDef",
    "AsyncInvokeSummaryTypeDef",
    "BidirectionalInputPayloadPartTypeDef",
    "BidirectionalOutputPayloadPartTypeDef",
    "BlobTypeDef",
    "CachePointBlockTypeDef",
    "ContentBlockDeltaEventTypeDef",
    "ContentBlockDeltaTypeDef",
    "ContentBlockOutputTypeDef",
    "ContentBlockStartEventTypeDef",
    "ContentBlockStartTypeDef",
    "ContentBlockStopEventTypeDef",
    "ContentBlockTypeDef",
    "ContentBlockUnionTypeDef",
    "ConverseMetricsTypeDef",
    "ConverseOutputTypeDef",
    "ConverseRequestTypeDef",
    "ConverseResponseTypeDef",
    "ConverseStreamMetadataEventTypeDef",
    "ConverseStreamMetricsTypeDef",
    "ConverseStreamOutputTypeDef",
    "ConverseStreamRequestTypeDef",
    "ConverseStreamResponseTypeDef",
    "ConverseStreamTraceTypeDef",
    "ConverseTraceTypeDef",
    "DocumentBlockOutputTypeDef",
    "DocumentBlockTypeDef",
    "DocumentBlockUnionTypeDef",
    "DocumentSourceOutputTypeDef",
    "DocumentSourceTypeDef",
    "DocumentSourceUnionTypeDef",
    "GetAsyncInvokeRequestTypeDef",
    "GetAsyncInvokeResponseTypeDef",
    "GuardrailAssessmentTypeDef",
    "GuardrailConfigurationTypeDef",
    "GuardrailContentBlockTypeDef",
    "GuardrailContentFilterTypeDef",
    "GuardrailContentPolicyAssessmentTypeDef",
    "GuardrailContextualGroundingFilterTypeDef",
    "GuardrailContextualGroundingPolicyAssessmentTypeDef",
    "GuardrailConverseContentBlockOutputTypeDef",
    "GuardrailConverseContentBlockTypeDef",
    "GuardrailConverseContentBlockUnionTypeDef",
    "GuardrailConverseImageBlockOutputTypeDef",
    "GuardrailConverseImageBlockTypeDef",
    "GuardrailConverseImageBlockUnionTypeDef",
    "GuardrailConverseImageSourceOutputTypeDef",
    "GuardrailConverseImageSourceTypeDef",
    "GuardrailConverseImageSourceUnionTypeDef",
    "GuardrailConverseTextBlockOutputTypeDef",
    "GuardrailConverseTextBlockTypeDef",
    "GuardrailConverseTextBlockUnionTypeDef",
    "GuardrailCoverageTypeDef",
    "GuardrailCustomWordTypeDef",
    "GuardrailImageBlockTypeDef",
    "GuardrailImageCoverageTypeDef",
    "GuardrailImageSourceTypeDef",
    "GuardrailInvocationMetricsTypeDef",
    "GuardrailManagedWordTypeDef",
    "GuardrailOutputContentTypeDef",
    "GuardrailPiiEntityFilterTypeDef",
    "GuardrailRegexFilterTypeDef",
    "GuardrailSensitiveInformationPolicyAssessmentTypeDef",
    "GuardrailStreamConfigurationTypeDef",
    "GuardrailTextBlockTypeDef",
    "GuardrailTextCharactersCoverageTypeDef",
    "GuardrailTopicPolicyAssessmentTypeDef",
    "GuardrailTopicTypeDef",
    "GuardrailTraceAssessmentTypeDef",
    "GuardrailUsageTypeDef",
    "GuardrailWordPolicyAssessmentTypeDef",
    "ImageBlockOutputTypeDef",
    "ImageBlockTypeDef",
    "ImageBlockUnionTypeDef",
    "ImageSourceOutputTypeDef",
    "ImageSourceTypeDef",
    "ImageSourceUnionTypeDef",
    "InferenceConfigurationTypeDef",
    "InternalServerExceptionTypeDef",
    "InvokeModelRequestTypeDef",
    "InvokeModelResponseTypeDef",
    "InvokeModelWithBidirectionalStreamInputTypeDef",
    "InvokeModelWithBidirectionalStreamOutputTypeDef",
    "InvokeModelWithBidirectionalStreamRequestTypeDef",
    "InvokeModelWithBidirectionalStreamResponseTypeDef",
    "InvokeModelWithResponseStreamRequestTypeDef",
    "InvokeModelWithResponseStreamResponseTypeDef",
    "ListAsyncInvokesRequestPaginateTypeDef",
    "ListAsyncInvokesRequestTypeDef",
    "ListAsyncInvokesResponseTypeDef",
    "MessageOutputTypeDef",
    "MessageStartEventTypeDef",
    "MessageStopEventTypeDef",
    "MessageTypeDef",
    "MessageUnionTypeDef",
    "ModelStreamErrorExceptionTypeDef",
    "ModelTimeoutExceptionTypeDef",
    "PaginatorConfigTypeDef",
    "PayloadPartTypeDef",
    "PerformanceConfigurationTypeDef",
    "PromptRouterTraceTypeDef",
    "PromptVariableValuesTypeDef",
    "ReasoningContentBlockDeltaTypeDef",
    "ReasoningContentBlockOutputTypeDef",
    "ReasoningContentBlockTypeDef",
    "ReasoningContentBlockUnionTypeDef",
    "ReasoningTextBlockTypeDef",
    "ResponseMetadataTypeDef",
    "ResponseStreamTypeDef",
    "S3LocationTypeDef",
    "ServiceUnavailableExceptionTypeDef",
    "SpecificToolChoiceTypeDef",
    "StartAsyncInvokeRequestTypeDef",
    "StartAsyncInvokeResponseTypeDef",
    "SystemContentBlockTypeDef",
    "TagTypeDef",
    "ThrottlingExceptionTypeDef",
    "TimestampTypeDef",
    "TokenUsageTypeDef",
    "ToolChoiceTypeDef",
    "ToolConfigurationTypeDef",
    "ToolInputSchemaTypeDef",
    "ToolResultBlockOutputTypeDef",
    "ToolResultBlockTypeDef",
    "ToolResultBlockUnionTypeDef",
    "ToolResultContentBlockOutputTypeDef",
    "ToolResultContentBlockTypeDef",
    "ToolResultContentBlockUnionTypeDef",
    "ToolSpecificationTypeDef",
    "ToolTypeDef",
    "ToolUseBlockDeltaTypeDef",
    "ToolUseBlockOutputTypeDef",
    "ToolUseBlockStartTypeDef",
    "ToolUseBlockTypeDef",
    "ToolUseBlockUnionTypeDef",
    "ValidationExceptionTypeDef",
    "VideoBlockOutputTypeDef",
    "VideoBlockTypeDef",
    "VideoBlockUnionTypeDef",
    "VideoSourceOutputTypeDef",
    "VideoSourceTypeDef",
    "VideoSourceUnionTypeDef",
)

class GuardrailOutputContentTypeDef(TypedDict):
    text: NotRequired[str]

class GuardrailUsageTypeDef(TypedDict):
    topicPolicyUnits: int
    contentPolicyUnits: int
    wordPolicyUnits: int
    sensitiveInformationPolicyUnits: int
    sensitiveInformationPolicyFreeUnits: int
    contextualGroundingPolicyUnits: int
    contentPolicyImageUnits: NotRequired[int]

class ResponseMetadataTypeDef(TypedDict):
    RequestId: str
    HTTPStatusCode: int
    HTTPHeaders: Dict[str, str]
    RetryAttempts: int
    HostId: NotRequired[str]

class AsyncInvokeS3OutputDataConfigTypeDef(TypedDict):
    s3Uri: str
    kmsKeyId: NotRequired[str]
    bucketOwner: NotRequired[str]

BlobTypeDef = Union[str, bytes, IO[Any], StreamingBody]
BidirectionalOutputPayloadPartTypeDef = TypedDict(
    "BidirectionalOutputPayloadPartTypeDef",
    {
        "bytes": NotRequired[bytes],
    },
)
CachePointBlockTypeDef = TypedDict(
    "CachePointBlockTypeDef",
    {
        "type": Literal["default"],
    },
)

class ReasoningContentBlockDeltaTypeDef(TypedDict):
    text: NotRequired[str]
    redactedContent: NotRequired[bytes]
    signature: NotRequired[str]

ToolUseBlockDeltaTypeDef = TypedDict(
    "ToolUseBlockDeltaTypeDef",
    {
        "input": str,
    },
)
ToolUseBlockOutputTypeDef = TypedDict(
    "ToolUseBlockOutputTypeDef",
    {
        "toolUseId": str,
        "name": str,
        "input": Dict[str, Any],
    },
)

class ToolUseBlockStartTypeDef(TypedDict):
    toolUseId: str
    name: str

class ContentBlockStopEventTypeDef(TypedDict):
    contentBlockIndex: int

class ConverseMetricsTypeDef(TypedDict):
    latencyMs: int

class GuardrailConfigurationTypeDef(TypedDict):
    guardrailIdentifier: str
    guardrailVersion: str
    trace: NotRequired[GuardrailTraceType]

class InferenceConfigurationTypeDef(TypedDict):
    maxTokens: NotRequired[int]
    temperature: NotRequired[float]
    topP: NotRequired[float]
    stopSequences: NotRequired[Sequence[str]]

class PerformanceConfigurationTypeDef(TypedDict):
    latency: NotRequired[PerformanceConfigLatencyType]

class PromptVariableValuesTypeDef(TypedDict):
    text: NotRequired[str]

class TokenUsageTypeDef(TypedDict):
    inputTokens: int
    outputTokens: int
    totalTokens: int
    cacheReadInputTokens: NotRequired[int]
    cacheWriteInputTokens: NotRequired[int]

class ConverseStreamMetricsTypeDef(TypedDict):
    latencyMs: int

class InternalServerExceptionTypeDef(TypedDict):
    message: NotRequired[str]

class MessageStartEventTypeDef(TypedDict):
    role: ConversationRoleType

class MessageStopEventTypeDef(TypedDict):
    stopReason: StopReasonType
    additionalModelResponseFields: NotRequired[Dict[str, Any]]

class ModelStreamErrorExceptionTypeDef(TypedDict):
    message: NotRequired[str]
    originalStatusCode: NotRequired[int]
    originalMessage: NotRequired[str]

class ServiceUnavailableExceptionTypeDef(TypedDict):
    message: NotRequired[str]

class ThrottlingExceptionTypeDef(TypedDict):
    message: NotRequired[str]

class ValidationExceptionTypeDef(TypedDict):
    message: NotRequired[str]

class GuardrailStreamConfigurationTypeDef(TypedDict):
    guardrailIdentifier: str
    guardrailVersion: str
    trace: NotRequired[GuardrailTraceType]
    streamProcessingMode: NotRequired[GuardrailStreamProcessingModeType]

class PromptRouterTraceTypeDef(TypedDict):
    invokedModelId: NotRequired[str]

DocumentSourceOutputTypeDef = TypedDict(
    "DocumentSourceOutputTypeDef",
    {
        "bytes": NotRequired[bytes],
    },
)

class GetAsyncInvokeRequestTypeDef(TypedDict):
    invocationArn: str

class GuardrailTextBlockTypeDef(TypedDict):
    text: str
    qualifiers: NotRequired[Sequence[GuardrailContentQualifierType]]

GuardrailContentFilterTypeDef = TypedDict(
    "GuardrailContentFilterTypeDef",
    {
        "type": GuardrailContentFilterTypeType,
        "confidence": GuardrailContentFilterConfidenceType,
        "action": GuardrailContentPolicyActionType,
        "filterStrength": NotRequired[GuardrailContentFilterStrengthType],
        "detected": NotRequired[bool],
    },
)
GuardrailContextualGroundingFilterTypeDef = TypedDict(
    "GuardrailContextualGroundingFilterTypeDef",
    {
        "type": GuardrailContextualGroundingFilterTypeType,
        "threshold": float,
        "score": float,
        "action": GuardrailContextualGroundingPolicyActionType,
        "detected": NotRequired[bool],
    },
)

class GuardrailConverseTextBlockOutputTypeDef(TypedDict):
    text: str
    qualifiers: NotRequired[List[GuardrailConverseContentQualifierType]]

GuardrailConverseImageSourceOutputTypeDef = TypedDict(
    "GuardrailConverseImageSourceOutputTypeDef",
    {
        "bytes": NotRequired[bytes],
    },
)

class GuardrailConverseTextBlockTypeDef(TypedDict):
    text: str
    qualifiers: NotRequired[Sequence[GuardrailConverseContentQualifierType]]

class GuardrailImageCoverageTypeDef(TypedDict):
    guarded: NotRequired[int]
    total: NotRequired[int]

class GuardrailTextCharactersCoverageTypeDef(TypedDict):
    guarded: NotRequired[int]
    total: NotRequired[int]

class GuardrailCustomWordTypeDef(TypedDict):
    match: str
    action: GuardrailWordPolicyActionType
    detected: NotRequired[bool]

GuardrailManagedWordTypeDef = TypedDict(
    "GuardrailManagedWordTypeDef",
    {
        "match": str,
        "type": Literal["PROFANITY"],
        "action": GuardrailWordPolicyActionType,
        "detected": NotRequired[bool],
    },
)
GuardrailPiiEntityFilterTypeDef = TypedDict(
    "GuardrailPiiEntityFilterTypeDef",
    {
        "match": str,
        "type": GuardrailPiiEntityTypeType,
        "action": GuardrailSensitiveInformationPolicyActionType,
        "detected": NotRequired[bool],
    },
)

class GuardrailRegexFilterTypeDef(TypedDict):
    action: GuardrailSensitiveInformationPolicyActionType
    name: NotRequired[str]
    match: NotRequired[str]
    regex: NotRequired[str]
    detected: NotRequired[bool]

GuardrailTopicTypeDef = TypedDict(
    "GuardrailTopicTypeDef",
    {
        "name": str,
        "type": Literal["DENY"],
        "action": GuardrailTopicPolicyActionType,
        "detected": NotRequired[bool],
    },
)
ImageSourceOutputTypeDef = TypedDict(
    "ImageSourceOutputTypeDef",
    {
        "bytes": NotRequired[bytes],
    },
)

class ModelTimeoutExceptionTypeDef(TypedDict):
    message: NotRequired[str]

class PaginatorConfigTypeDef(TypedDict):
    MaxItems: NotRequired[int]
    PageSize: NotRequired[int]
    StartingToken: NotRequired[str]

TimestampTypeDef = Union[datetime, str]
PayloadPartTypeDef = TypedDict(
    "PayloadPartTypeDef",
    {
        "bytes": NotRequired[bytes],
    },
)

class ReasoningTextBlockTypeDef(TypedDict):
    text: str
    signature: NotRequired[str]

class S3LocationTypeDef(TypedDict):
    uri: str
    bucketOwner: NotRequired[str]

class SpecificToolChoiceTypeDef(TypedDict):
    name: str

class TagTypeDef(TypedDict):
    key: str
    value: str

class ToolInputSchemaTypeDef(TypedDict):
    json: NotRequired[Mapping[str, Any]]

ToolUseBlockTypeDef = TypedDict(
    "ToolUseBlockTypeDef",
    {
        "toolUseId": str,
        "name": str,
        "input": Mapping[str, Any],
    },
)

class InvokeModelResponseTypeDef(TypedDict):
    body: StreamingBody
    contentType: str
    performanceConfigLatency: PerformanceConfigLatencyType
    ResponseMetadata: ResponseMetadataTypeDef

class StartAsyncInvokeResponseTypeDef(TypedDict):
    invocationArn: str
    ResponseMetadata: ResponseMetadataTypeDef

class AsyncInvokeOutputDataConfigTypeDef(TypedDict):
    s3OutputDataConfig: NotRequired[AsyncInvokeS3OutputDataConfigTypeDef]

BidirectionalInputPayloadPartTypeDef = TypedDict(
    "BidirectionalInputPayloadPartTypeDef",
    {
        "bytes": NotRequired[BlobTypeDef],
    },
)
DocumentSourceTypeDef = TypedDict(
    "DocumentSourceTypeDef",
    {
        "bytes": NotRequired[BlobTypeDef],
    },
)
GuardrailConverseImageSourceTypeDef = TypedDict(
    "GuardrailConverseImageSourceTypeDef",
    {
        "bytes": NotRequired[BlobTypeDef],
    },
)
GuardrailImageSourceTypeDef = TypedDict(
    "GuardrailImageSourceTypeDef",
    {
        "bytes": NotRequired[BlobTypeDef],
    },
)
ImageSourceTypeDef = TypedDict(
    "ImageSourceTypeDef",
    {
        "bytes": NotRequired[BlobTypeDef],
    },
)

class InvokeModelRequestTypeDef(TypedDict):
    modelId: str
    body: NotRequired[BlobTypeDef]
    contentType: NotRequired[str]
    accept: NotRequired[str]
    trace: NotRequired[TraceType]
    guardrailIdentifier: NotRequired[str]
    guardrailVersion: NotRequired[str]
    performanceConfigLatency: NotRequired[PerformanceConfigLatencyType]

class InvokeModelWithResponseStreamRequestTypeDef(TypedDict):
    modelId: str
    body: NotRequired[BlobTypeDef]
    contentType: NotRequired[str]
    accept: NotRequired[str]
    trace: NotRequired[TraceType]
    guardrailIdentifier: NotRequired[str]
    guardrailVersion: NotRequired[str]
    performanceConfigLatency: NotRequired[PerformanceConfigLatencyType]

class ContentBlockDeltaTypeDef(TypedDict):
    text: NotRequired[str]
    toolUse: NotRequired[ToolUseBlockDeltaTypeDef]
    reasoningContent: NotRequired[ReasoningContentBlockDeltaTypeDef]

class ContentBlockStartTypeDef(TypedDict):
    toolUse: NotRequired[ToolUseBlockStartTypeDef]

DocumentBlockOutputTypeDef = TypedDict(
    "DocumentBlockOutputTypeDef",
    {
        "format": DocumentFormatType,
        "name": str,
        "source": DocumentSourceOutputTypeDef,
    },
)

class GuardrailContentPolicyAssessmentTypeDef(TypedDict):
    filters: List[GuardrailContentFilterTypeDef]

class GuardrailContextualGroundingPolicyAssessmentTypeDef(TypedDict):
    filters: NotRequired[List[GuardrailContextualGroundingFilterTypeDef]]

GuardrailConverseImageBlockOutputTypeDef = TypedDict(
    "GuardrailConverseImageBlockOutputTypeDef",
    {
        "format": GuardrailConverseImageFormatType,
        "source": GuardrailConverseImageSourceOutputTypeDef,
    },
)
GuardrailConverseTextBlockUnionTypeDef = Union[
    GuardrailConverseTextBlockTypeDef, GuardrailConverseTextBlockOutputTypeDef
]

class GuardrailCoverageTypeDef(TypedDict):
    textCharacters: NotRequired[GuardrailTextCharactersCoverageTypeDef]
    images: NotRequired[GuardrailImageCoverageTypeDef]

class GuardrailWordPolicyAssessmentTypeDef(TypedDict):
    customWords: List[GuardrailCustomWordTypeDef]
    managedWordLists: List[GuardrailManagedWordTypeDef]

class GuardrailSensitiveInformationPolicyAssessmentTypeDef(TypedDict):
    piiEntities: List[GuardrailPiiEntityFilterTypeDef]
    regexes: List[GuardrailRegexFilterTypeDef]

class GuardrailTopicPolicyAssessmentTypeDef(TypedDict):
    topics: List[GuardrailTopicTypeDef]

ImageBlockOutputTypeDef = TypedDict(
    "ImageBlockOutputTypeDef",
    {
        "format": ImageFormatType,
        "source": ImageSourceOutputTypeDef,
    },
)

class InvokeModelWithBidirectionalStreamOutputTypeDef(TypedDict):
    chunk: NotRequired[BidirectionalOutputPayloadPartTypeDef]
    internalServerException: NotRequired[InternalServerExceptionTypeDef]
    modelStreamErrorException: NotRequired[ModelStreamErrorExceptionTypeDef]
    validationException: NotRequired[ValidationExceptionTypeDef]
    throttlingException: NotRequired[ThrottlingExceptionTypeDef]
    modelTimeoutException: NotRequired[ModelTimeoutExceptionTypeDef]
    serviceUnavailableException: NotRequired[ServiceUnavailableExceptionTypeDef]

class ListAsyncInvokesRequestPaginateTypeDef(TypedDict):
    submitTimeAfter: NotRequired[TimestampTypeDef]
    submitTimeBefore: NotRequired[TimestampTypeDef]
    statusEquals: NotRequired[AsyncInvokeStatusType]
    sortBy: NotRequired[Literal["SubmissionTime"]]
    sortOrder: NotRequired[SortOrderType]
    PaginationConfig: NotRequired[PaginatorConfigTypeDef]

class ListAsyncInvokesRequestTypeDef(TypedDict):
    submitTimeAfter: NotRequired[TimestampTypeDef]
    submitTimeBefore: NotRequired[TimestampTypeDef]
    statusEquals: NotRequired[AsyncInvokeStatusType]
    maxResults: NotRequired[int]
    nextToken: NotRequired[str]
    sortBy: NotRequired[Literal["SubmissionTime"]]
    sortOrder: NotRequired[SortOrderType]

class ResponseStreamTypeDef(TypedDict):
    chunk: NotRequired[PayloadPartTypeDef]
    internalServerException: NotRequired[InternalServerExceptionTypeDef]
    modelStreamErrorException: NotRequired[ModelStreamErrorExceptionTypeDef]
    validationException: NotRequired[ValidationExceptionTypeDef]
    throttlingException: NotRequired[ThrottlingExceptionTypeDef]
    modelTimeoutException: NotRequired[ModelTimeoutExceptionTypeDef]
    serviceUnavailableException: NotRequired[ServiceUnavailableExceptionTypeDef]

class ReasoningContentBlockOutputTypeDef(TypedDict):
    reasoningText: NotRequired[ReasoningTextBlockTypeDef]
    redactedContent: NotRequired[bytes]

class ReasoningContentBlockTypeDef(TypedDict):
    reasoningText: NotRequired[ReasoningTextBlockTypeDef]
    redactedContent: NotRequired[BlobTypeDef]

VideoSourceOutputTypeDef = TypedDict(
    "VideoSourceOutputTypeDef",
    {
        "bytes": NotRequired[bytes],
        "s3Location": NotRequired[S3LocationTypeDef],
    },
)
VideoSourceTypeDef = TypedDict(
    "VideoSourceTypeDef",
    {
        "bytes": NotRequired[BlobTypeDef],
        "s3Location": NotRequired[S3LocationTypeDef],
    },
)
ToolChoiceTypeDef = TypedDict(
    "ToolChoiceTypeDef",
    {
        "auto": NotRequired[Mapping[str, Any]],
        "any": NotRequired[Mapping[str, Any]],
        "tool": NotRequired[SpecificToolChoiceTypeDef],
    },
)

class ToolSpecificationTypeDef(TypedDict):
    name: str
    inputSchema: ToolInputSchemaTypeDef
    description: NotRequired[str]

ToolUseBlockUnionTypeDef = Union[ToolUseBlockTypeDef, ToolUseBlockOutputTypeDef]

class AsyncInvokeSummaryTypeDef(TypedDict):
    invocationArn: str
    modelArn: str
    submitTime: datetime
    outputDataConfig: AsyncInvokeOutputDataConfigTypeDef
    clientRequestToken: NotRequired[str]
    status: NotRequired[AsyncInvokeStatusType]
    failureMessage: NotRequired[str]
    lastModifiedTime: NotRequired[datetime]
    endTime: NotRequired[datetime]

class GetAsyncInvokeResponseTypeDef(TypedDict):
    invocationArn: str
    modelArn: str
    clientRequestToken: str
    status: AsyncInvokeStatusType
    failureMessage: str
    submitTime: datetime
    lastModifiedTime: datetime
    endTime: datetime
    outputDataConfig: AsyncInvokeOutputDataConfigTypeDef
    ResponseMetadata: ResponseMetadataTypeDef

class StartAsyncInvokeRequestTypeDef(TypedDict):
    modelId: str
    modelInput: Mapping[str, Any]
    outputDataConfig: AsyncInvokeOutputDataConfigTypeDef
    clientRequestToken: NotRequired[str]
    tags: NotRequired[Sequence[TagTypeDef]]

class InvokeModelWithBidirectionalStreamInputTypeDef(TypedDict):
    chunk: NotRequired[BidirectionalInputPayloadPartTypeDef]

DocumentSourceUnionTypeDef = Union[DocumentSourceTypeDef, DocumentSourceOutputTypeDef]
GuardrailConverseImageSourceUnionTypeDef = Union[
    GuardrailConverseImageSourceTypeDef, GuardrailConverseImageSourceOutputTypeDef
]
GuardrailImageBlockTypeDef = TypedDict(
    "GuardrailImageBlockTypeDef",
    {
        "format": GuardrailImageFormatType,
        "source": GuardrailImageSourceTypeDef,
    },
)
ImageSourceUnionTypeDef = Union[ImageSourceTypeDef, ImageSourceOutputTypeDef]

class ContentBlockDeltaEventTypeDef(TypedDict):
    delta: ContentBlockDeltaTypeDef
    contentBlockIndex: int

class ContentBlockStartEventTypeDef(TypedDict):
    start: ContentBlockStartTypeDef
    contentBlockIndex: int

class GuardrailConverseContentBlockOutputTypeDef(TypedDict):
    text: NotRequired[GuardrailConverseTextBlockOutputTypeDef]
    image: NotRequired[GuardrailConverseImageBlockOutputTypeDef]

class GuardrailInvocationMetricsTypeDef(TypedDict):
    guardrailProcessingLatency: NotRequired[int]
    usage: NotRequired[GuardrailUsageTypeDef]
    guardrailCoverage: NotRequired[GuardrailCoverageTypeDef]

class InvokeModelWithBidirectionalStreamResponseTypeDef(TypedDict):
    body: EventStream[InvokeModelWithBidirectionalStreamOutputTypeDef]
    ResponseMetadata: ResponseMetadataTypeDef

class InvokeModelWithResponseStreamResponseTypeDef(TypedDict):
    body: EventStream[ResponseStreamTypeDef]
    contentType: str
    performanceConfigLatency: PerformanceConfigLatencyType
    ResponseMetadata: ResponseMetadataTypeDef

ReasoningContentBlockUnionTypeDef = Union[
    ReasoningContentBlockTypeDef, ReasoningContentBlockOutputTypeDef
]
VideoBlockOutputTypeDef = TypedDict(
    "VideoBlockOutputTypeDef",
    {
        "format": VideoFormatType,
        "source": VideoSourceOutputTypeDef,
    },
)
VideoSourceUnionTypeDef = Union[VideoSourceTypeDef, VideoSourceOutputTypeDef]

class ToolTypeDef(TypedDict):
    toolSpec: NotRequired[ToolSpecificationTypeDef]
    cachePoint: NotRequired[CachePointBlockTypeDef]

class ListAsyncInvokesResponseTypeDef(TypedDict):
    asyncInvokeSummaries: List[AsyncInvokeSummaryTypeDef]
    ResponseMetadata: ResponseMetadataTypeDef
    nextToken: NotRequired[str]

class InvokeModelWithBidirectionalStreamRequestTypeDef(TypedDict):
    modelId: str
    body: EventStream[InvokeModelWithBidirectionalStreamInputTypeDef]

DocumentBlockTypeDef = TypedDict(
    "DocumentBlockTypeDef",
    {
        "format": DocumentFormatType,
        "name": str,
        "source": DocumentSourceUnionTypeDef,
    },
)
GuardrailConverseImageBlockTypeDef = TypedDict(
    "GuardrailConverseImageBlockTypeDef",
    {
        "format": GuardrailConverseImageFormatType,
        "source": GuardrailConverseImageSourceUnionTypeDef,
    },
)

class GuardrailContentBlockTypeDef(TypedDict):
    text: NotRequired[GuardrailTextBlockTypeDef]
    image: NotRequired[GuardrailImageBlockTypeDef]

ImageBlockTypeDef = TypedDict(
    "ImageBlockTypeDef",
    {
        "format": ImageFormatType,
        "source": ImageSourceUnionTypeDef,
    },
)

class GuardrailAssessmentTypeDef(TypedDict):
    topicPolicy: NotRequired[GuardrailTopicPolicyAssessmentTypeDef]
    contentPolicy: NotRequired[GuardrailContentPolicyAssessmentTypeDef]
    wordPolicy: NotRequired[GuardrailWordPolicyAssessmentTypeDef]
    sensitiveInformationPolicy: NotRequired[GuardrailSensitiveInformationPolicyAssessmentTypeDef]
    contextualGroundingPolicy: NotRequired[GuardrailContextualGroundingPolicyAssessmentTypeDef]
    invocationMetrics: NotRequired[GuardrailInvocationMetricsTypeDef]

class ToolResultContentBlockOutputTypeDef(TypedDict):
    json: NotRequired[Dict[str, Any]]
    text: NotRequired[str]
    image: NotRequired[ImageBlockOutputTypeDef]
    document: NotRequired[DocumentBlockOutputTypeDef]
    video: NotRequired[VideoBlockOutputTypeDef]

VideoBlockTypeDef = TypedDict(
    "VideoBlockTypeDef",
    {
        "format": VideoFormatType,
        "source": VideoSourceUnionTypeDef,
    },
)

class ToolConfigurationTypeDef(TypedDict):
    tools: Sequence[ToolTypeDef]
    toolChoice: NotRequired[ToolChoiceTypeDef]

DocumentBlockUnionTypeDef = Union[DocumentBlockTypeDef, DocumentBlockOutputTypeDef]
GuardrailConverseImageBlockUnionTypeDef = Union[
    GuardrailConverseImageBlockTypeDef, GuardrailConverseImageBlockOutputTypeDef
]

class ApplyGuardrailRequestTypeDef(TypedDict):
    guardrailIdentifier: str
    guardrailVersion: str
    source: GuardrailContentSourceType
    content: Sequence[GuardrailContentBlockTypeDef]
    outputScope: NotRequired[GuardrailOutputScopeType]

ImageBlockUnionTypeDef = Union[ImageBlockTypeDef, ImageBlockOutputTypeDef]

class ApplyGuardrailResponseTypeDef(TypedDict):
    usage: GuardrailUsageTypeDef
    action: GuardrailActionType
    actionReason: str
    outputs: List[GuardrailOutputContentTypeDef]
    assessments: List[GuardrailAssessmentTypeDef]
    guardrailCoverage: GuardrailCoverageTypeDef
    ResponseMetadata: ResponseMetadataTypeDef

class GuardrailTraceAssessmentTypeDef(TypedDict):
    modelOutput: NotRequired[List[str]]
    inputAssessment: NotRequired[Dict[str, GuardrailAssessmentTypeDef]]
    outputAssessments: NotRequired[Dict[str, List[GuardrailAssessmentTypeDef]]]
    actionReason: NotRequired[str]

class ToolResultBlockOutputTypeDef(TypedDict):
    toolUseId: str
    content: List[ToolResultContentBlockOutputTypeDef]
    status: NotRequired[ToolResultStatusType]

VideoBlockUnionTypeDef = Union[VideoBlockTypeDef, VideoBlockOutputTypeDef]

class GuardrailConverseContentBlockTypeDef(TypedDict):
    text: NotRequired[GuardrailConverseTextBlockUnionTypeDef]
    image: NotRequired[GuardrailConverseImageBlockUnionTypeDef]

class ConverseStreamTraceTypeDef(TypedDict):
    guardrail: NotRequired[GuardrailTraceAssessmentTypeDef]
    promptRouter: NotRequired[PromptRouterTraceTypeDef]

class ConverseTraceTypeDef(TypedDict):
    guardrail: NotRequired[GuardrailTraceAssessmentTypeDef]
    promptRouter: NotRequired[PromptRouterTraceTypeDef]

class ContentBlockOutputTypeDef(TypedDict):
    text: NotRequired[str]
    image: NotRequired[ImageBlockOutputTypeDef]
    document: NotRequired[DocumentBlockOutputTypeDef]
    video: NotRequired[VideoBlockOutputTypeDef]
    toolUse: NotRequired[ToolUseBlockOutputTypeDef]
    toolResult: NotRequired[ToolResultBlockOutputTypeDef]
    guardContent: NotRequired[GuardrailConverseContentBlockOutputTypeDef]
    cachePoint: NotRequired[CachePointBlockTypeDef]
    reasoningContent: NotRequired[ReasoningContentBlockOutputTypeDef]

class ToolResultContentBlockTypeDef(TypedDict):
    json: NotRequired[Mapping[str, Any]]
    text: NotRequired[str]
    image: NotRequired[ImageBlockUnionTypeDef]
    document: NotRequired[DocumentBlockUnionTypeDef]
    video: NotRequired[VideoBlockUnionTypeDef]

GuardrailConverseContentBlockUnionTypeDef = Union[
    GuardrailConverseContentBlockTypeDef, GuardrailConverseContentBlockOutputTypeDef
]

class ConverseStreamMetadataEventTypeDef(TypedDict):
    usage: TokenUsageTypeDef
    metrics: ConverseStreamMetricsTypeDef
    trace: NotRequired[ConverseStreamTraceTypeDef]
    performanceConfig: NotRequired[PerformanceConfigurationTypeDef]

class MessageOutputTypeDef(TypedDict):
    role: ConversationRoleType
    content: List[ContentBlockOutputTypeDef]

ToolResultContentBlockUnionTypeDef = Union[
    ToolResultContentBlockTypeDef, ToolResultContentBlockOutputTypeDef
]

class SystemContentBlockTypeDef(TypedDict):
    text: NotRequired[str]
    guardContent: NotRequired[GuardrailConverseContentBlockUnionTypeDef]
    cachePoint: NotRequired[CachePointBlockTypeDef]

class ConverseStreamOutputTypeDef(TypedDict):
    messageStart: NotRequired[MessageStartEventTypeDef]
    contentBlockStart: NotRequired[ContentBlockStartEventTypeDef]
    contentBlockDelta: NotRequired[ContentBlockDeltaEventTypeDef]
    contentBlockStop: NotRequired[ContentBlockStopEventTypeDef]
    messageStop: NotRequired[MessageStopEventTypeDef]
    metadata: NotRequired[ConverseStreamMetadataEventTypeDef]
    internalServerException: NotRequired[InternalServerExceptionTypeDef]
    modelStreamErrorException: NotRequired[ModelStreamErrorExceptionTypeDef]
    validationException: NotRequired[ValidationExceptionTypeDef]
    throttlingException: NotRequired[ThrottlingExceptionTypeDef]
    serviceUnavailableException: NotRequired[ServiceUnavailableExceptionTypeDef]

class ConverseOutputTypeDef(TypedDict):
    message: NotRequired[MessageOutputTypeDef]

class ToolResultBlockTypeDef(TypedDict):
    toolUseId: str
    content: Sequence[ToolResultContentBlockUnionTypeDef]
    status: NotRequired[ToolResultStatusType]

class ConverseStreamResponseTypeDef(TypedDict):
    stream: EventStream[ConverseStreamOutputTypeDef]
    ResponseMetadata: ResponseMetadataTypeDef

class ConverseResponseTypeDef(TypedDict):
    output: ConverseOutputTypeDef
    stopReason: StopReasonType
    usage: TokenUsageTypeDef
    metrics: ConverseMetricsTypeDef
    additionalModelResponseFields: Dict[str, Any]
    trace: ConverseTraceTypeDef
    performanceConfig: PerformanceConfigurationTypeDef
    ResponseMetadata: ResponseMetadataTypeDef

ToolResultBlockUnionTypeDef = Union[ToolResultBlockTypeDef, ToolResultBlockOutputTypeDef]

class ContentBlockTypeDef(TypedDict):
    text: NotRequired[str]
    image: NotRequired[ImageBlockUnionTypeDef]
    document: NotRequired[DocumentBlockUnionTypeDef]
    video: NotRequired[VideoBlockUnionTypeDef]
    toolUse: NotRequired[ToolUseBlockUnionTypeDef]
    toolResult: NotRequired[ToolResultBlockUnionTypeDef]
    guardContent: NotRequired[GuardrailConverseContentBlockUnionTypeDef]
    cachePoint: NotRequired[CachePointBlockTypeDef]
    reasoningContent: NotRequired[ReasoningContentBlockUnionTypeDef]

ContentBlockUnionTypeDef = Union[ContentBlockTypeDef, ContentBlockOutputTypeDef]

class MessageTypeDef(TypedDict):
    role: ConversationRoleType
    content: Sequence[ContentBlockUnionTypeDef]

MessageUnionTypeDef = Union[MessageTypeDef, MessageOutputTypeDef]

class ConverseRequestTypeDef(TypedDict):
    modelId: str
    messages: NotRequired[Sequence[MessageUnionTypeDef]]
    system: NotRequired[Sequence[SystemContentBlockTypeDef]]
    inferenceConfig: NotRequired[InferenceConfigurationTypeDef]
    toolConfig: NotRequired[ToolConfigurationTypeDef]
    guardrailConfig: NotRequired[GuardrailConfigurationTypeDef]
    additionalModelRequestFields: NotRequired[Mapping[str, Any]]
    promptVariables: NotRequired[Mapping[str, PromptVariableValuesTypeDef]]
    additionalModelResponseFieldPaths: NotRequired[Sequence[str]]
    requestMetadata: NotRequired[Mapping[str, str]]
    performanceConfig: NotRequired[PerformanceConfigurationTypeDef]

class ConverseStreamRequestTypeDef(TypedDict):
    modelId: str
    messages: NotRequired[Sequence[MessageUnionTypeDef]]
    system: NotRequired[Sequence[SystemContentBlockTypeDef]]
    inferenceConfig: NotRequired[InferenceConfigurationTypeDef]
    toolConfig: NotRequired[ToolConfigurationTypeDef]
    guardrailConfig: NotRequired[GuardrailStreamConfigurationTypeDef]
    additionalModelRequestFields: NotRequired[Mapping[str, Any]]
    promptVariables: NotRequired[Mapping[str, PromptVariableValuesTypeDef]]
    additionalModelResponseFieldPaths: NotRequired[Sequence[str]]
    requestMetadata: NotRequired[Mapping[str, str]]
    performanceConfig: NotRequired[PerformanceConfigurationTypeDef]
