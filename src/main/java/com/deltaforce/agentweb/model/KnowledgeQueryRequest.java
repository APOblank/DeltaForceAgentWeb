package com.deltaforce.agentweb.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class KnowledgeQueryRequest {

    @NotBlank(message = "query cannot be blank")
    @Size(max = 2000, message = "Cypher 查询不能超过 2000 个字符")
    private String query;

    @Min(value = 1, message = "limit 必须大于 0")
    @Max(value = 200, message = "limit 不能超过 200")
    private Integer limit;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
