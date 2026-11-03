package com.example.equipmentmanagement.dto;

/** Optional for normal completion; required by the service for a legacy review hold. */
public record CompleteRepairOrderRequest(String reviewNote) {
    public CompleteRepairOrderRequest {
        if (reviewNote != null) {
            reviewNote = reviewNote.trim();
            if (reviewNote.isEmpty() || reviewNote.length() > 500)
                throw new IllegalArgumentException("人工核对说明须为1至500字");
        }
    }
}
