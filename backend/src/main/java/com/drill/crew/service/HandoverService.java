package com.drill.crew.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drill.crew.common.BizException;
import com.drill.crew.domain.Borehole;
import com.drill.crew.domain.HandoverVersion;
import com.drill.crew.dto.HandoverActionRequest;
import com.drill.crew.dto.HandoverIssueRequest;
import com.drill.crew.repository.BoreholeRepository;
import com.drill.crew.repository.HandoverVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 终孔交接台：发起快照、退回整改、确认接收、返开和历史版本还原。
 */
@Service
public class HandoverService {

    private final HandoverVersionRepository versionRepository;
    private final BoreholeRepository boreholeRepository;
    private final HandoverSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    public HandoverService(HandoverVersionRepository versionRepository, BoreholeRepository boreholeRepository,
                           HandoverSnapshotService snapshotService, ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.boreholeRepository = boreholeRepository;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listActive() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Borehole hole : boreholeRepository.findAll()) {
            HandoverVersion active = versionRepository.findActiveByBoreholeId(hole.getId()).orElse(null);
            list.add(summarize(hole, active));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long boreholeId) {
        Borehole hole = boreholeRepository.findById(boreholeId)
                .orElseThrow(() -> new BizException("钻孔不存在：id=" + boreholeId));
        HandoverVersion active = versionRepository.findActiveByBoreholeId(boreholeId).orElse(null);
        List<HandoverVersion> history = versionRepository.findByBoreholeIdOrderByVersionNoDescIdDesc(boreholeId)
                .stream().filter(v -> !Boolean.TRUE.equals(v.getActive())).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("borehole", hole);
        result.put("active", active == null ? null : toView(active, true));
        result.put("history", history.stream().map(v -> toView(v, false)).toList());
        if (active != null && active.getSnapshotJson() == null
                && HandoverVersion.STATUS_RECTIFYING.equals(active.getStatus())) {
            result.put("reopenLiveSnapshot", snapshotService.buildSnapshot(boreholeId, active.getIssuesJson()));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> start(Long boreholeId, HandoverActionRequest request) {
        Borehole hole = boreholeRepository.findByIdForUpdate(boreholeId)
                .orElseThrow(() -> new BizException("钻孔不存在：id=" + boreholeId));
        HandoverVersion existing = versionRepository.findActiveByBoreholeId(boreholeId).orElse(null);
        if (existing != null) {
            throw new BizException("钻孔【" + hole.getHoleNo() + "】已存在有效交接版本（" + existing.getStatus()
                    + "），不能重复发起");
        }
        if (hole.getActualDepth() == null || hole.getActualDepth().signum() <= 0) {
            throw new BizException("实际孔深必须大于 0 m 才能发起终孔交接");
        }

        Map<String, Object> snapshot = snapshotService.buildSnapshot(boreholeId, List.of());
        HandoverVersion version = baseVersion(hole);
        version.setStatus(HandoverVersion.STATUS_PENDING);
        version.setActive(true);
        version.setSnapshotDepth(hole.getActualDepth());
        version.setSnapshotJson(snapshot);
        version.setIssuesJson(copyIssues(HandoverSnapshotService.issuesOf(snapshot)));
        version.setSubmitNote(trim(request == null ? null : request.note()));
        version.setSubmittedBy(defaultActor(request == null ? null : request.actor(), "现场班组"));
        version.setSubmittedAt(LocalDateTime.now());
        version.setSuperseded(false);
        return Map.of("active", toView(versionRepository.save(version), true));
    }

    @Transactional
    public Map<String, Object> resubmit(Long id, HandoverActionRequest request) {
        HandoverVersion old = getActive(id);
        checkExpected(old, request == null ? null : request.expectedVersion());
        if (!HandoverVersion.STATUS_RECTIFYING.equals(old.getStatus())) {
            throw new BizException("只有待整改版本可以整改后重新提交，当前状态：" + old.getStatus());
        }
        Borehole hole = boreholeRepository.findById(old.getBoreholeId())
                .orElseThrow(() -> new BizException("钻孔不存在：id=" + old.getBoreholeId()));

        Map<String, Object> snapshot = snapshotService.buildSnapshot(hole.getId(), old.getIssuesJson());
        long openIssues = HandoverSnapshotService.openCount(HandoverSnapshotService.issuesOf(snapshot));
        old.setActive(null);
        old.setStatus(old.isSuperseded() ? "已重提" : "已退回");

        HandoverVersion version = baseVersion(hole);
        version.setStatus(HandoverVersion.STATUS_PENDING);
        version.setActive(true);
        version.setSnapshotDepth(hole.getActualDepth());
        version.setSnapshotJson(snapshot);
        version.setIssuesJson(copyIssues(HandoverSnapshotService.issuesOf(snapshot)));
        version.setSubmitNote(trim(request == null ? null : request.note()));
        version.setSubmittedBy(defaultActor(request == null ? null : request.actor(), "现场班组"));
        version.setSubmittedAt(LocalDateTime.now());
        version.setSourceVersionId(old.getId());
        version.setReopenOfVersion(old.getReopenOfVersion());
        version.setRejectReason(old.getRejectReason());
        version.setLastInspectionJson(old.getLastInspectionJson());
        version.setSuperseded(false);
        versionRepository.save(old);
        HandoverVersion saved = versionRepository.save(version);
        return Map.of("active", toView(saved, true), "previousVersion", old.getVersionNo(),
                "openIssueCount", openIssues);
    }

    @Transactional
    public Map<String, Object> reject(Long id, HandoverActionRequest request) {
        requireReason(request == null ? null : request.reason(), "退回原因不能为空");
        HandoverVersion version = getActive(id);
        checkExpected(version, request == null ? null : request.expectedVersion());
        if (!HandoverVersion.STATUS_PENDING.equals(version.getStatus())) {
            throw new BizException("只有待检查版本可以退回整改，当前状态：" + version.getStatus());
        }
        String actor = defaultActor(request.actor(), "地质负责人");
        Map<String, Object> inspection = new LinkedHashMap<>();
        inspection.put("checkedAt", LocalDateTime.now());
        inspection.put("checkedBy", actor);
        inspection.put("reason", request.reason().trim());
        inspection.put("note", trim(request.note()));
        inspection.put("openIssueCount", HandoverSnapshotService.openCount(version.getIssuesJson()));
        inspection.put("issues", copyIssues(version.getIssuesJson()));

        version.setStatus(HandoverVersion.STATUS_RECTIFYING);
        version.setCheckedBy(actor);
        version.setCheckedAt(LocalDateTime.now());
        version.setRejectReason(request.reason().trim());
        version.setLastInspectionJson(inspection);
        return Map.of("active", toView(versionRepository.save(version), true));
    }

    @Transactional
    public Map<String, Object> accept(Long id, HandoverActionRequest request) {
        HandoverVersion version = getActive(id);
        checkExpected(version, request == null ? null : request.expectedVersion());
        if (!HandoverVersion.STATUS_PENDING.equals(version.getStatus())) {
            throw new BizException("只有待检查版本可以确认接收，当前状态：" + version.getStatus());
        }
        long open = HandoverSnapshotService.openCount(version.getIssuesJson());
        if (open > 0) {
            throw new BizException("仍有 " + open + " 个待处理问题未清零，只能保存整改进度，不能确认接收");
        }
        version.setStatus(HandoverVersion.STATUS_ACCEPTED);
        version.setCheckedBy(defaultActor(request == null ? null : request.actor(), "地质负责人"));
        version.setCheckedAt(LocalDateTime.now());
        if (request != null && request.note() != null && !request.note().trim().isEmpty()) {
            version.setSubmitNote(request.note().trim());
        }
        return Map.of("active", toView(versionRepository.save(version), true));
    }

    @Transactional
    public Map<String, Object> saveIssueProgress(Long id, HandoverIssueRequest request) {
        if (request == null || isBlank(request.issueCode())) {
            throw new BizException("问题项编号不能为空");
        }
        HandoverVersion version = getActive(id);
        if (!HandoverVersion.STATUS_RECTIFYING.equals(version.getStatus())) {
            throw new BizException("只有待整改版本可以保存整改进度");
        }
        Map<String, Object> issue = version.getIssuesJson().stream()
                .filter(i -> request.issueCode().equals(String.valueOf(i.get("code"))))
                .findFirst().orElseThrow(() -> new BizException("问题项不存在或不属于当前版本：" + request.issueCode()));
        if (request.resolved() && !"param".equals(issue.get("type"))) {
            throw new BizException("回次、岩性层或班报问题必须修正源资料并重新提交，由新一轮快照核验，不能手工标记清零");
        }
        if (request.resolved() && isBlank(request.resolution())) {
            throw new BizException("参数异常清零前必须填写处理说明");
        }
        issue.put("resolved", request.resolved());
        issue.put("resolution", isBlank(request.resolution()) ? null : request.resolution().trim());
        issue.put("updatedBy", defaultActor(request.actor(), "现场班组"));
        issue.put("updatedAt", LocalDateTime.now());
        return Map.of("active", toView(versionRepository.save(version), true));
    }

    @Transactional
    public Map<String, Object> reopen(Long id, HandoverActionRequest request) {
        requireReason(request == null ? null : request.reason(), "返开原因不能为空");
        HandoverVersion accepted = getActive(id);
        checkExpected(accepted, request == null ? null : request.expectedVersion());
        if (!HandoverVersion.STATUS_ACCEPTED.equals(accepted.getStatus())) {
            throw new BizException("只有已接收版本可以办理返开，当前状态：" + accepted.getStatus());
        }
        Borehole hole = boreholeRepository.findById(accepted.getBoreholeId())
                .orElseThrow(() -> new BizException("钻孔不存在：id=" + accepted.getBoreholeId()));

        accepted.setActive(null);
        accepted.setStatus("已失效");
        accepted.setSuperseded(true);
        accepted.setSupersededAt(LocalDateTime.now());

        HandoverVersion version = baseVersion(hole);
        version.setStatus(HandoverVersion.STATUS_RECTIFYING);
        version.setActive(true);
        version.setSnapshotDepth(accepted.getSnapshotDepth());
        version.setSnapshotJson(null);
        version.setIssuesJson(new ArrayList<>());
        version.setSubmittedBy(accepted.getSubmittedBy());
        version.setSubmittedAt(LocalDateTime.now());
        version.setReopenReason(request.reason().trim());
        version.setReopenedBy(defaultActor(request.actor(), "地质负责人"));
        version.setReopenedAt(LocalDateTime.now());
        version.setReopenOfVersion(accepted.getVersionNo());
        version.setSourceVersionId(accepted.getId());
        version.setSuperseded(false);
        versionRepository.save(accepted);
        HandoverVersion saved = versionRepository.save(version);
        return Map.of("active", toView(saved, true), "invalidatedVersion", accepted.getVersionNo());
    }

    // ---------------- 视图与规则 ----------------

    private HandoverVersion baseVersion(Borehole hole) {
        HandoverVersion version = new HandoverVersion();
        version.setBoreholeId(hole.getId());
        version.setVersionNo(versionRepository.maxVersionNo(hole.getId()) + 1);
        return version;
    }

    private HandoverVersion getActive(Long id) {
        HandoverVersion version = versionRepository.findById(id)
                .orElseThrow(() -> new BizException("交接版本不存在：id=" + id));
        if (!Boolean.TRUE.equals(version.getActive())) {
            throw new BizException("该版本已成为历史版本，请打开当前有效版本处理");
        }
        return version;
    }

    private void checkExpected(HandoverVersion version, Long expected) {
        if (expected != null && !expected.equals(version.getLockVersion())) {
            throw new ObjectOptimisticLockingFailureException(HandoverVersion.class, version.getId());
        }
    }

    private Map<String, Object> summarize(Borehole hole, HandoverVersion version) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("boreholeId", hole.getId());
        map.put("holeNo", hole.getHoleNo());
        map.put("actualDepth", hole.getActualDepth());
        map.put("designDepth", hole.getDesignDepth());
        map.put("boreholeStatus", hole.getStatus());
        if (version == null) {
            map.put("handoverId", null);
            map.put("versionNo", null);
            map.put("status", "未发起");
            map.put("openIssueCount", 0);
            map.put("issueCount", 0);
            return map;
        }
        map.put("handoverId", version.getId());
        map.put("versionNo", version.getVersionNo());
        map.put("status", version.getStatus());
        map.put("snapshotDepth", version.getSnapshotDepth());
        map.put("openIssueCount", HandoverSnapshotService.openCount(version.getIssuesJson()));
        map.put("issueCount", version.getIssuesJson() == null ? 0 : version.getIssuesJson().size());
        map.put("submittedAt", version.getSubmittedAt());
        map.put("superseded", version.isSuperseded());
        return map;
    }

    private Map<String, Object> toView(HandoverVersion version, boolean active) {
        Map<String, Object> map = objectMapper.convertValue(version, Map.class);
        map.put("active", active);
        map.put("openIssueCount", HandoverSnapshotService.openCount(version.getIssuesJson()));
        map.put("issueCount", version.getIssuesJson() == null ? 0 : version.getIssuesJson().size());
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> copyIssues(List<Map<String, Object>> issues) {
        if (issues == null) {
            return new ArrayList<>();
        }
        return objectMapper.convertValue(issues, List.class);
    }

    private void requireReason(String reason, String message) {
        if (isBlank(reason)) {
            throw new BizException(message);
        }
    }

    private String defaultActor(String actor, String fallback) {
        return isBlank(actor) ? fallback : actor.trim();
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
