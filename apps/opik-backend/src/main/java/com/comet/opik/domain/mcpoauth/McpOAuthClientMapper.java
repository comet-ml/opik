package com.comet.opik.domain.mcpoauth;

import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(imports = StringUtils.class)
interface McpOAuthClientMapper {

    McpOAuthClientMapper INSTANCE = Mappers.getMapper(McpOAuthClientMapper.class);

    @Mapping(target = "id", source = "clientId")
    @Mapping(target = "name", expression = "java(StringUtils.defaultIfBlank(request.clientName(), clientId))")
    @Mapping(target = "redirectUris", source = "request.redirectUris")
    @Mapping(target = "logoUri", source = "request.logoUri")
    @Mapping(target = "ownerUserName", ignore = true)
    McpOAuthClient toClient(ClientRegistrationRequest request, String clientId);
}
