package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.google.cloud.vertexai.api.FunctionCall;
import com.google.cloud.vertexai.api.FunctionDeclaration;
import com.google.cloud.vertexai.api.Tool;
import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.vertexai.SchemaHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FunctionCallHelper {

    private static final Gson GSON = new Gson();

    static FunctionCall fromToolExecutionRequest(ToolExecutionRequest toolExecutionRequest) {
        FunctionCall.Builder fnCallBuilder = FunctionCall.newBuilder()
                .setName(toolExecutionRequest.name());

        Struct.Builder structBuilder = Struct.newBuilder();
        try {
            JsonFormat.parser().merge(toolExecutionRequest.arguments(), structBuilder);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        Struct argsStruct = structBuilder.build();
        fnCallBuilder.setArgs(argsStruct);

        return fnCallBuilder.build();
    }

    static List<ToolExecutionRequest> fromFunctionCalls(List<FunctionCall> functionCalls) {
        List<ToolExecutionRequest> toolExecutionRequests = new ArrayList<>();

        for (FunctionCall functionCall : functionCalls) {
            ToolExecutionRequest.Builder builder = ToolExecutionRequest.builder()
                    .name(functionCall.getName());

            Map<String, Object> callArgsMap = new HashMap<>();
            Struct callArgs = functionCall.getArgs();
            Map<String, Value> callArgsFieldsMap = callArgs.getFieldsMap();
            callArgsFieldsMap.forEach((key, value) -> callArgsMap.put(key, unwrapProtoValue(value)));

            String serializedArgsMap = GSON.toJson(callArgsMap);
            builder.arguments(serializedArgsMap);

            toolExecutionRequests.add(builder.build());
        }

        return toolExecutionRequests;
    }

    static Object unwrapProtoValue(Value value) {
        Object unwrappedValue;
        switch (value.getKindCase()) {
            case NUMBER_VALUE :
                unwrappedValue = value.getNumberValue();
                break;
            case STRING_VALUE :
                unwrappedValue = value.getStringValue();
                break;
            case BOOL_VALUE :
                unwrappedValue = value.getBoolValue();
                break;
            case STRUCT_VALUE :
                HashMap<String, Object> mapForStruct = new HashMap<>();
                value.getStructValue().getFieldsMap()
                        .forEach((key, val) -> mapForStruct.put(key, unwrapProtoValue(val)));
                unwrappedValue = mapForStruct;
                break;
            case LIST_VALUE :
                unwrappedValue = value.getListValue().getValuesList().stream().map(FunctionCallHelper::unwrapProtoValue)
                        .collect(Collectors.toList());
                break;
            default : // NULL_VALUE, KIND_NOT_SET, and default
                unwrappedValue = null;
                break;
        }
        return unwrappedValue;
    }

    static Tool convertToolSpecifications(List<ToolSpecification> toolSpecifications) {
        Tool.Builder tool = Tool.newBuilder();

        for (ToolSpecification toolSpecification : toolSpecifications) {
            FunctionDeclaration.Builder fnBuilder = FunctionDeclaration.newBuilder()
                    .setName(toolSpecification.name());

            if (toolSpecification.description() != null) {
                fnBuilder.setDescription(toolSpecification.description());
            }

            if (toolSpecification.parameters() != null) {
                fnBuilder.setParameters(SchemaHelper.from(toolSpecification.parameters()));
            }

            tool.addFunctionDeclarations(fnBuilder.build());
        }

        return tool.build();
    }
}
