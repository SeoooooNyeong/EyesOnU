package com.ssafy.eyesonu.missingcase.service;

import com.ssafy.eyesonu.missingcase.domain.MissingCaseRow;
import com.ssafy.eyesonu.missingcase.dto.admin.AdminCaseRoutePointResponse;
import com.ssafy.eyesonu.missingcase.dto.admin.AdminCaseRouteResponse;
import com.ssafy.eyesonu.missingcase.mapper.AdminCaseRouteMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCaseRouteService {
    private final CaseQueryService caseQueryService;
    private final AdminCaseRouteMapper routeMapper;

    public AdminCaseRouteService(CaseQueryService caseQueryService, AdminCaseRouteMapper routeMapper) {
        this.caseQueryService = caseQueryService;
        this.routeMapper = routeMapper;
    }

    @Transactional(readOnly = true)
    public AdminCaseRouteResponse findRoute(Long caseId) {
        MissingCaseRow missingCase = caseQueryService.require(caseId);
        List<AdminCaseRoutePointResponse> points = routeMapper.findConfirmedRoutePoints(caseId).stream()
                .map(AdminCaseRoutePointResponse::from)
                .toList();
        return new AdminCaseRouteResponse(
                missingCase.getId(), missingCase.getCaseNumber(), missingCase.getMissingName(), points);
    }
}
