package com.deltaforce.agentweb.model;

import javax.validation.constraints.NotBlank;

public class KnowledgeQueryRequest {

    @NotBlank(message = "query cannot be blank")
    private String query;

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
