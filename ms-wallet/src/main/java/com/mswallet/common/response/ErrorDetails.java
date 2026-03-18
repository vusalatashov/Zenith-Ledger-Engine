package com.mswallet.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * Error Details DTO.
 * Encapsulates error specifics, including validation failures and request paths.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorDetails {
    private final String code;
    private final String path;
    private final Map<String, String> validationErrors;
}