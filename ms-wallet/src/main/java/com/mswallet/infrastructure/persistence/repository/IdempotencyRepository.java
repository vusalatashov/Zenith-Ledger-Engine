package com.mswallet.infrastructure.persistence.repository;

import com.mswallet.infrastructure.persistence.entity.IdempotentRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface IdempotencyRepository extends JpaRepository<IdempotentRequestEntity, String> {
}