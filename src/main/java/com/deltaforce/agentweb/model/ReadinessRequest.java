package com.deltaforce.agentweb.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class ReadinessRequest {

    @NotNull(message = "目标战备不能为空")
    @Min(value = 1, message = "目标战备必须大于 0")
    private Long targetCombat;

    @Min(value = 1, message = "方案数量至少为 1")
    @Max(value = 10, message = "方案数量不能超过 10")
    private Integer topK = 3;

    public Long getTargetCombat() {
        return targetCombat;
    }

    public void setTargetCombat(Long targetCombat) {
        this.targetCombat = targetCombat;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }
}
