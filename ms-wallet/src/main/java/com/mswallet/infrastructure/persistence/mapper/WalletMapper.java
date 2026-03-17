package com.mswallet.infrastructure.persistence.mapper;

import com.mswallet.api.dto.response.WalletResponse;
import com.mswallet.domain.model.Wallet;
import com.mswallet.infrastructure.persistence.entity.WalletEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WalletMapper {

    Wallet toDomain(WalletEntity entity);

    @Mapping(target = "version", ignore = true)
    WalletEntity toEntity(Wallet domain);

    WalletResponse toDto(Wallet domain);
}