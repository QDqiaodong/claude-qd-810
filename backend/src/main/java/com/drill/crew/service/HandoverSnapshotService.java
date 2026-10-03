package com.drill.crew.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drill.crew.common.BizException;
import com.drill.crew.domain.Borehole;
import com.drill.crew.domain.CoreRound;
import com.drill.crew.domain.DrillParamSeries;
import com.drill.crew.domain.LithoLayer;
import com.drill.crew.domain.ShiftReport;
import com.drill.crew.repository.BoreholeRepository;
import com.drill.crew.repository.CoreRoundRepository;
import com.drill.crew.repository.DrillParamSeriesRepository;
import com.drill.crew.repository.LithoLayerRepository;
import com.drill.crew.repository.ShiftReportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 以发起交接时的实际孔深生成不可变资料快照，并产出可追踪、可跳转的问题清单。
 */
@Service
public class HandoverSnapshotService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final BoreholeRepository boreholeRepository;
    private final CoreRoundRepository roundRepository;
    private final LithoLayerRepository layerRepository;
    private final DrillParamSeriesRepository seriesRepository;
    private final ShiftReportRepository reportRepository;
    private final DrillParamAnalyzer analyzer;
    private final ObjectMapper objectMapper;

    public HandoverSnapshotService(BoreholeRepository boreholeRepository, CoreRoundRepository roundRepository,
                                   LithoLayerRepository layerRepository, DrillParamSeriesRepository seriesRepository,
                                   ShiftReportRepository reportRepository, DrillParamAnalyzer analyzer,
                                   ObjectMapper objectMapper) {
        this.boreholeRepository = boreholeRepository;
        this.roundRepository = roundRepository;
        this.layerRepository = layerRepository;
        this.seriesRepository = seriesRepository;
        this.reportRepository = reportRepository;
        this.analyzer = analyzer;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildSnapshot(Long boreholeId, List<Map<String, Object>> previousIssues) {
        Borehole hole = boreholeRepository.findById(boreholeId)
                .orElseThrow(() -> new BizException("钻孔不存在：id=" + boreholeId));
        BigDecimal depth = hole.getActualDepth();
        List<CoreRound> rounds = roundRepository.findByBoreholeIdOrderByRoundNoAsc(boreholeId);
        List<LithoLayer> layers = layerRepository.findByBoreholeIdOrderByFromDepthAsc(boreholeId);
        List<DrillParamSeries> seriesList = seriesRepository.findByBoreholeIdOrderByRecordDateDesc(boreholeId);
        List<ShiftReport> reports = reportRepository.findByBoreholeIdOrderByReportDateDesc(boreholeId);

        List<Map<String, Object>> issues = new ArrayList<>();
        checkRounds(depth, rounds, issues);
        checkLayers(depth, rounds, layers, issues);
        List<Map<String, Object>> analyses = checkParams(hole, seriesList, issues);
        checkReports(reports, issues);
        carryParamProgress(issues, previousIssues);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("borehole", objectMapper.convertValue(hole, Map.class));
        snapshot.put("snapshotDepth", depth);
        snapshot.put("capturedAt", LocalDateTime.now());
        snapshot.put("rounds", objectMapper.convertValue(rounds, List.class));
        snapshot.put("layers", objectMapper.convertValue(layers, List.class));
        snapshot.put("paramSeries", objectMapper.convertValue(seriesList, List.class));
        snapshot.put("paramAnalyses", analyses);
        snapshot.put("shiftReports", objectMapper.convertValue(reports, List.class));
        snapshot.put("issueCount", issues.size());
        snapshot.put("openIssueCount", issues.stream().filter(this::isOpen).count());
        snapshot.put("issues", issues);
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> issuesOf(Map<String, Object> snapshot) {
        Object value = snapshot == null ? null : snapshot.get("issues");
        return value instanceof List ? (List<Map<String, Object>>) value : List.of();
    }

    public static long openCount(List<Map<String, Object>> issues) {
        return issues == null ? 0 : issues.stream().filter(i -> !Boolean.TRUE.equals(i.get("resolved"))).count();
    }

    private void checkRounds(BigDecimal depth, List<CoreRound> rounds, List<Map<String, Object>> issues) {
        if (depth == null || depth.compareTo(BigDecimal.ZERO) <= 0) {
            addIssue(issues, "ROUND-DEPTH-0", "round", null, "实际孔深必须大于 0 才能发起终孔交接",
                    "请先在孔位台账校正实际孔深", "/holes", null);
            return;
        }
        if (rounds.isEmpty()) {
            addIssue(issues, "ROUND-EMPTY", "round", null, "缺少回次记录，无法证明全孔岩芯编录连续",
                    "请补齐从孔口到当前孔深的回次", "/core", null);
            return;
        }

        for (int i = 0; i < rounds.size(); i++) {
            CoreRound round = rounds.get(i);
            String target = "/core?boreholeId=" + round.getBoreholeId() + "&roundId=" + round.getId();
            if (round.getRoundNo() != i + 1) {
                addIssue(issues, "ROUND-NO-" + round.getRoundNo(), "round", round.getId(),
                        "第 " + (i + 1) + " 个位置的回次号为 " + round.getRoundNo() + "，回次号必须连续",
                        "调整或补录第 " + (i + 1) + " 回次", target, round.getRoundNo());
            }
            if (round.getToDepth().compareTo(depth) > 0) {
                addIssue(issues, "ROUND-RANGE-" + round.getId(), "round", round.getId(),
                        "第 " + round.getRoundNo() + " 回次止深 " + round.getToDepth() + " m 超出实际孔深 " + depth + " m",
                        "核对孔深和回次止深", target, round.getRoundNo());
            }
            if (round.getRecoveryRate() != null
                    && round.getRecoveryRate().compareTo(CoreLogService.LOW_RECOVERY) < 0
                    && (round.getLowRecoveryReason() == null || round.getLowRecoveryReason().trim().isEmpty())) {
                addIssue(issues, "ROUND-LOW-" + round.getId(), "round", round.getId(),
                        "第 " + round.getRoundNo() + " 回次采取率 " + round.getRecoveryRate() + "% 低于 "
                                + CoreLogService.LOW_RECOVERY + "%，缺少原因",
                        "补填低采取率原因", target, round.getRoundNo());
            }
            if (i == 0) {
                if (round.getFromDepth().abs().compareTo(TOLERANCE) > 0) {
                    addIssue(issues, "ROUND-START", "round", round.getId(),
                            "首回次应从 0.00 m 开始，当前起深为 " + round.getFromDepth() + " m",
                            "补齐孔口至首回次之间的进尺", target, round.getRoundNo());
                }
            } else {
                CoreRound prev = rounds.get(i - 1);
                BigDecimal gap = round.getFromDepth().subtract(prev.getToDepth());
                String code = "ROUND-CHAIN-" + prev.getId() + "-" + round.getId();
                if (gap.abs().compareTo(TOLERANCE) > 0) {
                    String message = gap.compareTo(BigDecimal.ZERO) > 0
                            ? "第 " + prev.getRoundNo() + "、" + round.getRoundNo() + " 回次之间存在 " + gap + " m 空隙"
                            : "第 " + prev.getRoundNo() + "、" + round.getRoundNo() + " 回次深度重叠 " + gap.abs() + " m";
                    addIssue(issues, code, "round", round.getId(), message, "回次必须在 0~" + depth + " m 内连续且不能重叠",
                            target, round.getRoundNo());
                }
            }
            if (i == rounds.size() - 1 && round.getToDepth().compareTo(depth) != 0) {
                addIssue(issues, "ROUND-END", "round", round.getId(),
                        "末回次止深为 " + round.getToDepth() + " m，未接到当前实际孔深 " + depth + " m",
                        "补齐末端进尺或校正实际孔深", target, round.getRoundNo());
            }
        }
    }

    private void checkLayers(BigDecimal depth, List<CoreRound> rounds, List<LithoLayer> layers,
                             List<Map<String, Object>> issues) {
        if (rounds.isEmpty() || depth.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Map<Long, List<LithoLayer>> byRound = layers.stream()
                .collect(Collectors.groupingBy(LithoLayer::getRoundId));
        for (CoreRound round : rounds) {
            List<LithoLayer> siblings = byRound.getOrDefault(round.getId(), new ArrayList<>()).stream()
                    .sorted(Comparator.comparing(LithoLayer::getFromDepth).thenComparing(LithoLayer::getLayerSeq))
                    .collect(Collectors.toList());
            String target = "/core?boreholeId=" + round.getBoreholeId() + "&roundId=" + round.getId();
            if (siblings.isEmpty()) {
                addIssue(issues, "LAYER-EMPTY-" + round.getId(), "layer", null,
                        "第 " + round.getRoundNo() + " 回次缺少岩性分层",
                        "按回次范围补齐岩性层", target, round.getRoundNo());
                continue;
            }

            LithoLayer first = siblings.get(0);
            LithoLayer last = siblings.get(siblings.size() - 1);
            if (first.getFromDepth().subtract(round.getFromDepth()).abs().compareTo(TOLERANCE) > 0) {
                addIssue(issues, "LAYER-START-" + round.getId(), "layer", first.getId(),
                        "第 " + round.getRoundNo() + " 回次首层层首 " + first.getFromDepth()
                                + " m 未接回次起深 " + round.getFromDepth() + " m",
                        "岩性层必须在回次范围内连续", "/core?layerId=" + first.getId(), round.getRoundNo());
            }
            if (last.getToDepth().subtract(round.getToDepth()).abs().compareTo(TOLERANCE) > 0) {
                addIssue(issues, "LAYER-END-" + round.getId(), "layer", last.getId(),
                        "第 " + round.getRoundNo() + " 回次末层层尾 " + last.getToDepth()
                                + " m 未接回次止深 " + round.getToDepth() + " m",
                        "岩性层必须覆盖到回次止深", "/core?layerId=" + last.getId(), round.getRoundNo());
            }
            for (int i = 1; i < siblings.size(); i++) {
                LithoLayer prev = siblings.get(i - 1);
                LithoLayer curr = siblings.get(i);
                BigDecimal gap = curr.getFromDepth().subtract(prev.getToDepth());
                if (gap.abs().compareTo(TOLERANCE) > 0) {
                    String message = gap.compareTo(BigDecimal.ZERO) > 0
                            ? "第 " + round.getRoundNo() + " 回次岩性层存在 " + gap + " m 空隙"
                            : "第 " + round.getRoundNo() + " 回次岩性层重叠 " + gap.abs() + " m";
                    addIssue(issues, "LAYER-CHAIN-" + prev.getId() + "-" + curr.getId(), "layer", curr.getId(),
                            message, "岩性层必须在回次范围内首尾相接、不能重叠",
                            "/core?layerId=" + curr.getId(), round.getRoundNo());
                }
            }
        }

        List<LithoLayer> orphan = layers.stream()
                .filter(l -> rounds.stream().noneMatch(r -> Objects.equals(r.getId(), l.getRoundId())))
                .toList();
        orphan.forEach(l -> addIssue(issues, "LAYER-ORPHAN-" + l.getId(), "layer", l.getId(),
                "岩性层 " + l.getLithology() + " 找不到所属回次",
                "重新挂接有效回次", "/core?layerId=" + l.getId(), null));
    }

    private List<Map<String, Object>> checkParams(Borehole hole, List<DrillParamSeries> seriesList,
                                                  List<Map<String, Object>> issues) {
        List<Map<String, Object>> analyses = new ArrayList<>();
        if (seriesList.isEmpty()) {
            addIssue(issues, "PARAM-EMPTY", "param", null, "缺少钻进参数记录",
                    "至少补录一份覆盖终孔施工的参数时序", "/param?boreholeId=" + hole.getId(), null);
            return analyses;
        }
        for (DrillParamSeries series : seriesList) {
            Map<String, Object> analysis = analyzer.analyze(series, hole);
            analyses.add(analysis);
            int abnormal = ((Number) analysis.getOrDefault("abnormalCount", 0)).intValue();
            if (abnormal > 0) {
                addIssue(issues, "PARAM-ABNORMAL-" + series.getId(), "param", series.getId(),
                        series.getRecordDate() + " " + series.getShiftType() + "班参数有 " + abnormal + " 个异常采样点",
                        "核对异常点并填写处理说明；确认为误采集可删除后重提",
                        "/param?boreholeId=" + series.getBoreholeId() + "&seriesId=" + series.getId(),
                        series.getRecordDate());
            }
        }
        return analyses;
    }

    private void checkReports(List<ShiftReport> reports, List<Map<String, Object>> issues) {
        if (reports.isEmpty()) {
            addIssue(issues, "REPORT-EMPTY", "report", null, "缺少班报记录",
                    "补齐施工期间班报并完成签字", "/report", null);
            return;
        }
        for (ShiftReport report : reports) {
            String title = report.getReportDate() + " " + report.getShiftType() + "班班报";
            if (report.getLeaderSign() == null || report.getLeaderSign().trim().isEmpty()) {
                addIssue(issues, "REPORT-SIGN-" + report.getId(), "report", report.getId(),
                        title + "缺少班组长签字",
                        "打开班报补签后重新提交交接",
                        "/report?reportId=" + report.getId(), report.getReportDate());
            }
        }
    }

    private void addIssue(List<Map<String, Object>> issues, String code, String type, Long targetId,
                          String message, String action, String target, Object context) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("code", code);
        issue.put("type", type);
        issue.put("targetId", targetId);
        issue.put("severity", "report".equals(type) ? "warning" : "error");
        issue.put("message", message);
        issue.put("requiredAction", action);
        issue.put("target", target);
        issue.put("context", context);
        issue.put("resolved", false);
        issue.put("resolution", null);
        issue.put("updatedAt", null);
        issue.put("updatedBy", null);
        issues.add(issue);
    }

    private void carryParamProgress(List<Map<String, Object>> issues, List<Map<String, Object>> previous) {
        if (previous == null || previous.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> old = previous.stream()
                .collect(Collectors.toMap(i -> String.valueOf(i.get("code")), i -> i, (a, b) -> a, LinkedHashMap::new));
        for (Map<String, Object> issue : issues) {
            Map<String, Object> oldIssue = old.get(String.valueOf(issue.get("code")));
            if (oldIssue != null && "param".equals(issue.get("type"))
                    && Boolean.TRUE.equals(oldIssue.get("resolved"))) {
                issue.put("resolved", true);
                issue.put("resolution", oldIssue.get("resolution"));
                issue.put("updatedAt", oldIssue.get("updatedAt"));
                issue.put("updatedBy", oldIssue.get("updatedBy"));
            }
        }
    }

    private boolean isOpen(Map<String, Object> issue) {
        return !Boolean.TRUE.equals(issue.get("resolved"));
    }
}
