package com.ssafy.eyesonu;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.eyesonu.admin.domain.Admin;
import com.ssafy.eyesonu.admin.mapper.AdminMapper;
import com.ssafy.eyesonu.audit.service.AuditService;
import com.ssafy.eyesonu.auth.config.SecurityConfig;
import com.ssafy.eyesonu.auth.device.MediaServerAuthenticationService;
import com.ssafy.eyesonu.auth.security.AdminPrincipal;
import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.common.exception.GlobalExceptionHandler;
import com.ssafy.eyesonu.missingcase.controller.admin.AdminCaseRouteController;
import com.ssafy.eyesonu.missingcase.dto.admin.AdminCaseRouteResponse;
import com.ssafy.eyesonu.missingcase.service.AdminCaseRouteService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ActiveProfiles("test")
@WebMvcTest(controllers = AdminCaseRouteController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminCaseRouteApiTests {
    private static final long ADMIN_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminMapper adminMapper;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private MediaServerAuthenticationService mediaServerAuthenticationService;

    @MockitoBean
    private AdminCaseRouteService routeService;

    @BeforeEach
    void activeAdmin() {
        when(adminMapper.findById(ADMIN_ID))
                .thenReturn(Optional.of(new Admin(ADMIN_ID, "admin", "hash", "Admin")));
    }

    @Test
    void returnsRouteEnvelopeWithoutCaching() throws Exception {
        when(routeService.findRoute(17L)).thenReturn(
                new AdminCaseRouteResponse(17L, "EFU-17", "김민수", List.of()));

        mockMvc.perform(get("/api/v1/admin/cases/17/route").with(adminAuthentication()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.caseId").value(17))
                .andExpect(jsonPath("$.data.points").isArray());
    }

    @Test
    void returnsResourceNotFoundForUnknownCase() throws Exception {
        when(routeService.findRoute(999L)).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "사건을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/v1/admin/cases/999/route").with(adminAuthentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private RequestPostProcessor adminAuthentication() {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin");
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }
}
