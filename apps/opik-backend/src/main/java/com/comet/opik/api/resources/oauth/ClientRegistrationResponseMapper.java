package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthConstants;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(imports = OAuthConstants.class)
interface ClientRegistrationResponseMapper {

    ClientRegistrationResponseMapper INSTANCE = Mappers.getMapper(ClientRegistrationResponseMapper.class);

    @Mapping(target = "clientName", source = "name")
    @Mapping(target = "clientIdIssuedAt", ignore = true)
    @Mapping(target = "tokenEndpointAuthMethod", expression = "java(OAuthConstants.AUTH_METHOD_NONE)")
    @Mapping(target = "grantTypes", expression = "java(OAuthConstants.DEFAULT_GRANT_TYPES)")
    @Mapping(target = "responseTypes", expression = "java(OAuthConstants.DEFAULT_RESPONSE_TYPES)")
    ClientRegistrationResponse toResponse(McpOAuthClient client);
}
