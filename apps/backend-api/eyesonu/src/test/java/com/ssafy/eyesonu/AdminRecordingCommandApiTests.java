package com.ssafy.eyesonu;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.eyesonu.admin.domain.Admin;
import com.ssafy.eyesonu.admin.domain.AdminRole;
import com.ssafy.eyesonu.admin.mapper.AdminMapper;
import com.ssafy.eyesonu.audit.service.AuditService;
import com.ssafy.eyesonu.auth.config.SecurityConfig;
import com.ssafy.eyesonu.auth.device.MediaServerAuthenticationService;
import com.ssafy.eyesonu.auth.security.AdminPrincipal;
import com.ssafy.eyesonu.common.exception.GlobalExceptionHandler;
import com.ssafy.eyesonu.recording.controller.admin.AdminRecordingCommandController;
import com.ssafy.eyesonu.recording.domain.Recording;
import com.ssafy.eyesonu.recording.dto.device.RecordingCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateRequest;
import com.ssafy.eyesonu.recording.dto.device.RecordingUploadUrlCreateResponse;
import com.ssafy.eyesonu.recording.service.AdminRecordingCommandService;
import com.ssafy.eyesonu.recording.service.RecordingCreateResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@ActiveProfiles("test")
@WebMvcTest(controllers = AdminRecordingCommandController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class AdminRecordingCommandApiTests {

    private static final String CAMERA_CODE = "camera-01";
    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OBJECT_KEY = "recordings/camera-01/2026/08/08/"
            + "20260808T010000000000Z_" + IDEMPOTENCY_KEY + ".mp4";
    private static final String BASE_PATH = "/api/v1/admin/cameras/" + CAMERA_CODE;
    private static final AdminPrincipal ADMIN = new AdminPrincipal(1L, "admin", AdminRole.ADMIN);
    private static final AdminPrincipal SUPER_ADMIN =
            new AdminPrincipal(2L, "root", AdminRole.SUPER_ADMIN);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminRecordingCommandService commandService;

    @MockitoBean
    private AdminMapper adminMapper;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private MediaServerAuthenticationService mediaServerAuthenticationService;

    @BeforeEach
    void activeAdmins() {
        when(adminMapper.findById(1L)).thenReturn(Optional.of(account(ADMIN)));
        when(adminMapper.findById(2L)).thenReturn(Optional.of(account(SUPER_ADMIN)));
    }

    @Test
    void bothAdminRolesCanCreateUploadUrlsWithCsrfAndNoStore() throws Exception {
        RecordingUploadUrlCreateRequest request = uploadRequest();
        RecordingUploadUrlCreateResponse response = new RecordingUploadUrlCreateResponse(
                OBJECT_KEY,
                "https://storage.example/upload",
                "video/mp4",
                900L,
                104_857_600L);
        when(commandService.createUploadUrl(eq(ADMIN), eq(CAMERA_CODE), eq(IDEMPOTENCY_KEY), eq(request)))
                .thenReturn(response);
        when(commandService.createUploadUrl(
                eq(SUPER_ADMIN), eq(CAMERA_CODE), eq(IDEMPOTENCY_KEY), eq(request)))
                .thenReturn(response);

        for (AdminPrincipal principal : new AdminPrincipal[] {ADMIN, SUPER_ADMIN}) {
            mockMvc.perform(post(BASE_PATH + "/recording-upload-urls")
                            .with(asAuthentication(principal))
                            .with(csrf())
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(uploadBody()))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Cache-Control", containsString("no-store")))
                    .andExpect(jsonPath("$.data.objectKey").value(OBJECT_KEY))
                    .andExpect(jsonPath("$.data.uploadUrl").value("https://storage.example/upload"))
                    .andExpect(jsonPath("$.data.contentType").value("video/mp4"))
                    .andExpect(jsonPath("$.data.expiresInSeconds").value(900))
                    .andExpect(jsonPath("$.data.maxFileSizeBytes").value(104857600));
        }
    }

    @Test
    void uploadUrlRequiresAuthenticationAndCsrfAndKeepsErrorsNoStore() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/recording-upload-urls")
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post(BASE_PATH + "/recording-upload-urls")
                        .with(asAuthentication(ADMIN))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody()))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        verifyNoInteractions(commandService);
    }

    @Test
    void completionReturnsCreatedThenOkForIdempotentReplay() throws Exception {
        RecordingCreateRequest request = createRequest();
        Recording recording = recording();
        when(commandService.create(eq(ADMIN), eq(CAMERA_CODE), eq(IDEMPOTENCY_KEY), eq(request)))
                .thenReturn(new RecordingCreateResult(recording, false))
                .thenReturn(new RecordingCreateResult(recording, true));

        var requestBuilder = post(BASE_PATH + "/recordings")
                .with(asAuthentication(ADMIN))
                .with(csrf())
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody());
        mockMvc.perform(requestBuilder)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(501))
                .andExpect(jsonPath("$.data.cameraId").value(11))
                .andExpect(jsonPath("$.data.fileSize").value(1024))
                .andExpect(jsonPath("$.data.duplicate").value(false));

        mockMvc.perform(post(BASE_PATH + "/recordings")
                        .with(asAuthentication(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(501))
                .andExpect(jsonPath("$.data.duplicate").value(true));
    }

    @Test
    void superAdminCanCompleteRecording() throws Exception {
        RecordingCreateRequest request = createRequest();
        when(commandService.create(
                eq(SUPER_ADMIN), eq(CAMERA_CODE), eq(IDEMPOTENCY_KEY), eq(request)))
                .thenReturn(new RecordingCreateResult(recording(), false));

        mockMvc.perform(post(BASE_PATH + "/recordings")
                        .with(asAuthentication(SUPER_ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(501))
                .andExpect(jsonPath("$.data.duplicate").value(false));
    }

    @Test
    void completionRequiresAuthenticationAndCsrf() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/recordings")
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(post(BASE_PATH + "/recordings")
                        .with(asAuthentication(SUPER_ADMIN))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(commandService);
    }

    @Test
    void requestBodyAndHeadersAreValidatedBeforeServiceCall() throws Exception {
        mockMvc.perform(post(BASE_PATH + "/recording-upload-urls")
                        .with(asAuthentication(ADMIN))
                        .with(csrf())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"2026-08-08T01:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post(BASE_PATH + "/recordings")
                        .with(asAuthentication(ADMIN))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(commandService);
    }

    private RequestPostProcessor asAuthentication(AdminPrincipal principal) {
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private Admin account(AdminPrincipal principal) {
        return new Admin(
                principal.getAdminId(),
                principal.getLoginId(),
                "hash",
                "Admin",
                principal.getRole(),
                true,
                Instant.parse("2026-08-01T00:00:00Z"));
    }

    private RecordingUploadUrlCreateRequest uploadRequest() {
        return new RecordingUploadUrlCreateRequest(
                OffsetDateTime.parse("2026-08-08T01:00:00Z"),
                OffsetDateTime.parse("2026-08-08T01:01:00Z"));
    }

    private RecordingCreateRequest createRequest() {
        return new RecordingCreateRequest(
                OffsetDateTime.parse("2026-08-08T01:00:00Z"),
                OffsetDateTime.parse("2026-08-08T01:01:00Z"),
                OBJECT_KEY);
    }

    private Recording recording() {
        return new Recording(
                501L,
                11L,
                Instant.parse("2026-08-08T01:00:00Z"),
                Instant.parse("2026-08-08T01:01:00Z"),
                OBJECT_KEY,
                1024L,
                Instant.parse("2026-08-08T02:00:00Z"));
    }

    private String uploadBody() {
        return """
                {"startTime":"2026-08-08T01:00:00Z","endTime":"2026-08-08T01:01:00Z"}
                """;
    }

    private String createBody() {
        return """
                {
                  "startTime":"2026-08-08T01:00:00Z",
                  "endTime":"2026-08-08T01:01:00Z",
                  "objectKey":"%s"
                }
                """.formatted(OBJECT_KEY);
    }
}
