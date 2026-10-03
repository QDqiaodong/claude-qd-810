package com.drill.crew.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drill.crew.common.BizException;
import com.drill.crew.domain.HandoverVersion;
import com.drill.crew.repository.HandoverVersionRepository;

/**
 * 已接收资料的写入关口。所有现有回次、分层、参数和班报录入入口都必须经过这里。
 */
@Service
public class HandoverGuard {

    private final HandoverVersionRepository handoverRepository;

    public HandoverGuard(HandoverVersionRepository handoverRepository) {
        this.handoverRepository = handoverRepository;
    }

    @Transactional(readOnly = true)
    public void requireWritable(Long boreholeId, String resourceName) {
        if (boreholeId == null) {
            return;
        }
        HandoverVersion active = handoverRepository.findActiveByBoreholeId(boreholeId).orElse(null);
        if (active == null) {
            return;
        }
        if (HandoverVersion.STATUS_PENDING.equals(active.getStatus())) {
            throw new BizException("钻孔资料正在等待地质检查，本轮快照已锁定；退回或接收后才能继续修改" + resourceName);
        }
        if (HandoverVersion.STATUS_ACCEPTED.equals(active.getStatus())) {
            throw new BizException("钻孔已完成终孔接收，" + resourceName + "已锁定；确需补充请先在交接台办理返开并填写原因");
        }
    }

    @Transactional(readOnly = true)
    public boolean isAccepted(Long boreholeId) {
        return handoverRepository.findActiveByBoreholeId(boreholeId)
                .filter(v -> HandoverVersion.STATUS_ACCEPTED.equals(v.getStatus()))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public boolean hasActiveHandover(Long boreholeId) {
        return handoverRepository.findActiveByBoreholeId(boreholeId).isPresent();
    }
}
