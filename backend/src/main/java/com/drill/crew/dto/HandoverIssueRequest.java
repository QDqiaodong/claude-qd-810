package com.drill.crew.dto;

/**
 * 待整改阶段保存某个问题项的整改进度。
 */
public record HandoverIssueRequest(
        String issueCode,
        boolean resolved,
        String resolution,
        String actor
) {
}
