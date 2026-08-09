package com.ssafy.eyesonu.missingcase.dto.admin;

import java.util.List;

public record AdminCaseRouteResponse(
        Long caseId,
        String caseNumber,
        String missingName,
        List<AdminCaseRoutePointResponse> points) {
}
