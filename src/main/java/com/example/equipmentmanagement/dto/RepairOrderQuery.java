package com.example.equipmentmanagement.dto;

/** Validated once when the HTTP query enters the application. */
public record RepairOrderQuery(int page, int pageSize, String status, Integer deviceId, String keyword) {
    public RepairOrderQuery {
        if (page < 1 || pageSize < 1 || pageSize > 100)
            throw new IllegalArgumentException("页码必须大于0，单页数量为1至100");
        if (status != null && !"PENDING".equals(status) && !"PROCESSING".equals(status) && !"COMPLETED".equals(status) && !"OPEN".equals(status))
            throw new IllegalArgumentException("状态不合法");
        if (deviceId != null && deviceId < 1)
            throw new IllegalArgumentException("设备ID不合法");
        keyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > 100)
            throw new IllegalArgumentException("搜索关键词不能超过100个字符");
    }
}
