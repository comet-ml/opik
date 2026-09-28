package com.comet.opik.domain.mcpoauth;

import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(imports = {StringUtils.class, McpOAuthClientUtils.class})
interface McpOAuthClientMapper {

    McpOAuthClientMapper INSTANCE = Mappers.getMapper(McpOAuthClientMapper.class);

    @Mapping(target = "id", source = "clientId")
    @Mapping(target = "name", expression = "java(StringUtils.defaultIfBlank(McpOAuthClientUtils.sanitizeDisplayText(request.clientName()), clientId))")
    @Mapping(target = "redirectUris", source = "request.redirectUris")
    @Mapping(target = "logoUri", expression = "java(McpOAuthClientUtils.sanitizeDisplayUri(request.logoUri()))")
    @Mapping(target = "softwareId", expression = "java(McpOAuthClientUtils.sanitizeDisplayText(request.softwareId()))")
    @Mapping(target = "softwareVersion", expression = "java(McpOAuthClientUtils.sanitizeDisplayText(request.softwareVersion()))")
    @Mapping(target = "clientUri", expression = "java(McpOAuthClientUtils.sanitizeDisplayUri(request.clientUri()))")
    @Mapping(target = "ownerUserName", ignore = true)
    McpOAuthClient toClient(ClientRegistrationRequest request, String clientId);

}
