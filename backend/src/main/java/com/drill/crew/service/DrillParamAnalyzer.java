package com.drill.crew.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.drill.crew.domain.Borehole;
import com.drill.crew.domain.DrillParamSeries;
import com.drill.crew.domain.ParamPoint;
import com.drill.crew.dto.ParamPointView;

/**
 * 钻进参数异常判定。交接快照和参数页面共用同一套阈值，避免检查口径不一致。
 */
@Component
public class DrillParamAnalyzer {

    public static final Map<String, BigDecimal[]> NORMAL_RANGE = Map.of(
            "pressure", new BigDecimal[] { new BigDecimal("8"), new BigDecimal("45") },
            "rotation", new BigDecimal[] { new BigDecimal("60"), new BigDecimal("450") },
            "pump", new BigDecimal[] { new BigDecimal("60"), new BigDecimal("260") },
            "mudDensity", new BigDecimal[] { new BigDecimal("1.05"), new BigDecimal("1.35") });

    public static final Map<String, String> LABEL = Map.of(
            "pressure", "钻压",
            "rotation", "转速",
            "pump", "泵量",
            "mudDensity", "泥浆密度");

    public Map<String, Object> analyze(DrillParamSeries series, Borehole hole) {
        List<ParamPoint> points = series.getPoints() == null ? List.of() : series.getPoints();
        List<ParamPointView> views = new ArrayList<>();
        int abnormalCount = 0;
        for (int i = 0; i < points.size(); i++) {
            ParamPointView v = ParamPointView.of(i, points.get(i));
            List<String> items = new ArrayList<>();
            checkRange("pressure", v.getPressure(), items);
            checkRange("rotation", v.getRotation(), items);
            checkRange("pump", v.getPump(), items);
            checkRange("mudDensity", v.getMudDensity(), items);
            v.setAbnormal(!items.isEmpty());
            v.setAbnormalItems(items);
            if (v.isAbnormal()) {
                abnormalCount++;
            }
            views.add(v);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seriesId", series.getId());
        result.put("boreholeId", series.getBoreholeId());
        result.put("holeNo", hole == null ? null : hole.getHoleNo());
        result.put("mineId", hole == null ? null : hole.getMineId());
        result.put("recordDate", series.getRecordDate());
        result.put("shiftType", series.getShiftType());
        result.put("operator", series.getOperator());
        result.put("pointCount", points.size());
        result.put("abnormalCount", abnormalCount);
        result.put("ranges", NORMAL_RANGE);
        result.put("points", views);
        return result;
    }

    private void checkRange(String key, BigDecimal value, List<String> items) {
        if (value == null) {
            return;
        }
        BigDecimal[] range = NORMAL_RANGE.get(key);
        if (value.compareTo(range[0]) < 0) {
            items.add(LABEL.get(key) + "偏低");
        } else if (value.compareTo(range[1]) > 0) {
            items.add(LABEL.get(key) + "偏高");
        }
    }
}
