package com.mswallet.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "idempotent_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotentRequestEntity {

    @Id
    private String requestKey;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}