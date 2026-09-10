package com.comet.opik.domain.mcpoauth;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

@Mapper
interface McpOAuthMapper {

    McpOAuthMapper INSTANCE = Mappers.getMapper(McpOAuthMapper.class);

    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "usedAt", ignore = true)
    McpOAuthCode toCode(CreateOAuthCodeCommand cmd, String id, String codeHash, String codeChallengeMethod,
            Instant expiresAt);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "tokenHash", source = "tokenHash")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "familyId", source = "familyId")
    @Mapping(target = "expiresAt", source = "expiresAt")
    @Mapping(target = "rotatedFromId", ignore = true)
    @Mapping(target = "issuedAt", ignore = true)
    @Mapping(target = "revokedAt", ignore = true)
    @Mapping(target = "revokedReason", ignore = true)
    McpOAuthToken toToken(McpOAuthCode code, String type, String id, String tokenHash, String familyId,
            Instant expiresAt);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "tokenHash", source = "tokenHash")
    @Mapping(target = "type", source = "type")
    @Mapping(target = "expiresAt", source = "expiresAt")
    @Mapping(target = "rotatedFromId", source = "source.id")
    @Mapping(target = "issuedAt", ignore = true)
    @Mapping(target = "revokedAt", ignore = true)
    @Mapping(target = "revokedReason", ignore = true)
    McpOAuthToken toRotatedToken(McpOAuthToken source, String type, String id, String tokenHash, Instant expiresAt);

    /** Identity and callback come from the burnt code; display metadata from the client registration. */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "clientId", source = "client.id")
    @Mapping(target = "clientName", source = "client.name")
    @Mapping(target = "softwareId", source = "client.softwareId")
    @Mapping(target = "softwareVersion", source = "client.softwareVersion")
    @Mapping(target = "logoUri", source = "client.logoUri")
    @Mapping(target = "firstConnectedAt", ignore = true)
    @Mapping(target = "lastConnectedAt", ignore = true)
    McpClientConnection toConnection(McpOAuthCode code, McpOAuthClient client, String id);
}
