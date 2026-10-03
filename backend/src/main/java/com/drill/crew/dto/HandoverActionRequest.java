package com.drill.crew.dto;

/**
 * 交接状态推进请求。expectedVersion 为页面打开时看到的 JPA 行版本，用于乐观锁。
 */
public record HandoverActionRequest(
        String actor,
        String reason,
        String note,
        Long expectedVersion
) {
}
