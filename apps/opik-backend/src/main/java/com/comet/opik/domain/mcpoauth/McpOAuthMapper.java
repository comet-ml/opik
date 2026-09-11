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
}
