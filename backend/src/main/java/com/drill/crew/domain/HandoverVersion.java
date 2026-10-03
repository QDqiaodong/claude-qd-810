package com.drill.crew.domain;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 钻孔终孔交接版本。每个钻孔至多有一个当前有效版本；历史版本保留不可变快照。
 */
@Entity
@Table(name = "borehole_handover_version")
@Getter
@Setter
public class HandoverVersion {

    public static final String STATUS_PENDING = "待检查";
    public static final String STATUS_RECTIFYING = "待整改";
    public static final String STATUS_ACCEPTED = "已接收";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "borehole_id", nullable = false)
    private Long boreholeId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 待检查 / 待整改 / 已接收（当前版本）；历史版本可显示已退回 / 已失效 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 当前有效版本为 true；被新提交或返开替代后置空，唯一索引只约束一行当前版本 */
    @Column(name = "is_active")
    private Boolean active;

    @Column(name = "snapshot_depth", precision = 8, scale = 2)
    private java.math.BigDecimal snapshotDepth;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_json", columnDefinition = "json")
    private Map<String, Object> snapshotJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "issues_json", nullable = false, columnDefinition = "json")
    private List<Map<String, Object>> issuesJson;

    @Column(name = "submit_note", length = 255)
    private String submitNote;

    @Column(name = "submitted_by", length = 32)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "checked_by", length = 32)
    private String checkedBy;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    /** 本轮地质检查结果与问题快照，退回给班组继续整改时保留 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_inspection_json", columnDefinition = "json")
    private Map<String, Object> lastInspectionJson;

    @Column(name = "reopen_reason", length = 255)
    private String reopenReason;

    @Column(name = "reopened_by", length = 32)
    private String reopenedBy;

    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopen_of_version")
    private Integer reopenOfVersion;

    /** 已被返开替代的接收版本仍保留，但明确标记失效 */
    @Column(name = "superseded", nullable = false)
    private boolean superseded;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    /** 来源版本：整改后再次提交时记录上一版本号 */
    @Column(name = "source_version_id")
    private Long sourceVersionId;

    /** JPA 乐观锁：两名负责人同时推进时，后提交者收到冲突并重新检查 */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;
}
