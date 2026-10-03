package com.drill.crew.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.drill.crew.common.ApiResponse;
import com.drill.crew.dto.HandoverActionRequest;
import com.drill.crew.dto.HandoverIssueRequest;
import com.drill.crew.service.HandoverService;

/**
 * 钻孔终孔交接台。
 */
@RestController
@RequestMapping("/api/handover")
public class HandoverController {

    private final HandoverService handoverService;

    public HandoverController(HandoverService handoverService) {
        this.handoverService = handoverService;
    }

    @GetMapping("/versions")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(handoverService.listActive());
    }

    @GetMapping("/boreholes/{boreholeId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long boreholeId) {
        return ApiResponse.success(handoverService.detail(boreholeId));
    }

    @PostMapping("/boreholes/{boreholeId}/start")
    public ApiResponse<Map<String, Object>> start(@PathVariable Long boreholeId,
                                                  @RequestBody(required = false) HandoverActionRequest request) {
        return ApiResponse.success("交接版本已生成", handoverService.start(boreholeId, request));
    }

    @PostMapping("/versions/{id}/resubmit")
    public ApiResponse<Map<String, Object>> resubmit(@PathVariable Long id,
                                                     @RequestBody(required = false) HandoverActionRequest request) {
        return ApiResponse.success("已形成新的待检查版本", handoverService.resubmit(id, request));
    }

    @PostMapping("/versions/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable Long id,
                                                   @RequestBody(required = false) HandoverActionRequest request) {
        return ApiResponse.success("已退回整改并保留检查结果", handoverService.reject(id, request));
    }

    @PostMapping("/versions/{id}/accept")
    public ApiResponse<Map<String, Object>> accept(@PathVariable Long id,
                                                   @RequestBody(required = false) HandoverActionRequest request) {
        return ApiResponse.success("钻孔资料已确认接收", handoverService.accept(id, request));
    }

    @PostMapping("/versions/{id}/issue-progress")
    public ApiResponse<Map<String, Object>> issueProgress(@PathVariable Long id,
                                                          @RequestBody HandoverIssueRequest request) {
        return ApiResponse.success("整改进度已保存", handoverService.saveIssueProgress(id, request));
    }

    @PostMapping("/versions/{id}/reopen")
    public ApiResponse<Map<String, Object>> reopen(@PathVariable Long id,
                                                   @RequestBody(required = false) HandoverActionRequest request) {
        return ApiResponse.success("已返开，原接收版本标记为已失效", handoverService.reopen(id, request));
    }
}
