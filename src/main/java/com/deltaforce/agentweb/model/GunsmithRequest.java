package com.deltaforce.agentweb.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class GunsmithRequest {

    @NotBlank(message = "枪械名称不能为空")
    @Size(max = 100, message = "枪械名称不能超过 100 个字符")
    private String weapon;
    @Min(value = 0, message = "预算不能为负数")
    @Max(value = 1000000000, message = "预算超出允许范围")
    private long budget;
    @Size(max = 50, message = "作战距离不能超过 50 个字符")
    private String range;
    @Size(max = 500, message = "偏好描述不能超过 500 个字符")
    private String preference;

    public String getWeapon() {
        return weapon;
    }

    public void setWeapon(String weapon) {
        this.weapon = weapon;
    }

    public long getBudget() {
        return budget;
    }

    public void setBudget(long budget) {
        this.budget = budget;
    }

    public String getRange() {
        return range;
    }

    public void setRange(String range) {
        this.range = range;
    }

    public String getPreference() {
        return preference;
    }

    public void setPreference(String preference) {
        this.preference = preference;
    }
}
