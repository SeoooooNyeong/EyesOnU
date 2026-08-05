package com.ssafy.eyesonu.missingcase.controller.admin;

import com.ssafy.eyesonu.common.api.ApiResponse;
import com.ssafy.eyesonu.missingcase.dto.admin.AdminCaseRouteResponse;
import com.ssafy.eyesonu.missingcase.service.AdminCaseRouteService;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/cases")
public class AdminCaseRouteController {
    private final AdminCaseRouteService routeService;

    public AdminCaseRouteController(AdminCaseRouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping("/{caseId}/route")
    public ResponseEntity<ApiResponse<AdminCaseRouteResponse>> findRoute(
            @PathVariable @Positive Long caseId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(routeService.findRoute(caseId)));
    }
}
