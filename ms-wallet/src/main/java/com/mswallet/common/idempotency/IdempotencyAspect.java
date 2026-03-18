package com.mswallet.common.idempotency;

import com.mswallet.infrastructure.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * Aspect to handle @Idempotent annotations.
 * Separates Redis caching concerns from core business logic.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotencyAspect {

    private final StringRedisTemplate redisTemplate;
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";

    @Around("@annotation(idempotent)")
    public Object handleIdempotency(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String idempotencyKey = request.getHeader(idempotent.headerName());

        if (!StringUtils.hasText(idempotencyKey)) {
            return joinPoint.proceed();
        }

        String redisKey = IDEMPOTENCY_PREFIX + idempotencyKey;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "PROCESSING", Duration.ofHours(idempotent.expireHours()));

        if (Boolean.FALSE.equals(isNew)) {
            log.warn("Duplicate request prevented for key: {}", idempotencyKey);
            throw new BusinessException("Duplicate request detected", "DUPLICATE_REQUEST", HttpStatus.CONFLICT);
        }

        try {
            return joinPoint.proceed();
        } catch (Exception e) {
            redisTemplate.delete(redisKey);
            throw e;
        }
    }
}