package com.example.equipmentmanagement.exception;

public class RepairReviewRequiredException extends RuntimeException {
    public RepairReviewRequiredException() {
        super("设备缺少可靠维修前状态，管理员须填写人工核对说明；完成后保留设备现状，自动恢复仍暂停");
    }
}
