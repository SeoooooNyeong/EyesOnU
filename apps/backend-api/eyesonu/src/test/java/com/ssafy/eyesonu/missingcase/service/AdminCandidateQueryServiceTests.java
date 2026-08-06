package com.ssafy.eyesonu.missingcase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.eyesonu.missingcase.domain.AdminCandidateDetectionRow;
import com.ssafy.eyesonu.missingcase.domain.AdminCandidateRow;
import com.ssafy.eyesonu.missingcase.domain.CandidateSourceType;
import com.ssafy.eyesonu.missingcase.dto.admin.AdminCandidateDetailResponse;
import com.ssafy.eyesonu.missingcase.dto.admin.AdminCandidateSearchCondition;
import com.ssafy.eyesonu.missingcase.mapper.AdminCandidateMapper;
import com.ssafy.eyesonu.storage.StorageObjectUrlSigner;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminCandidateQueryServiceTests {

    private final AdminCandidateMapper mapper = mock(AdminCandidateMapper.class);
    private final StorageObjectUrlSigner signer = mock(StorageObjectUrlSigner.class);
    private AdminCandidateQueryService service;

    @BeforeEach
    void setUp() {
        service = new AdminCandidateQueryService(mapper, signer);
    }

    @Test
    void returnsSignedUrlsInsteadOfObjectKeysInCandidateList() {
        AdminCandidateRow row = candidate();
        when(mapper.countCandidates(isNull(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(1L);
        when(mapper.findPage(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(),
                any(), any(), anyInt(), anyLong())).thenReturn(List.of(row));
        stubSignedUrls();

        AdminCandidatePageResult result = service.findAll(
                new AdminCandidateSearchCondition(null, null, null, null, null, null,
                        0, 20, "lastDetectedAt,desc"));

        assertEquals("https://storage.example/crop", result.candidates().getFirst().cropUrl());
        assertEquals(CandidateSourceType.REALTIME, result.candidates().getFirst().sourceType());
        verify(signer, never()).createGetUrl("frames/frame.jpg");
        verify(signer, never()).createGetUrl("cases/1/missing.jpg");
        verify(signer, never()).createGetUrl("recordings/1.mp4");
        verify(signer, never()).createGetUrl("clips/1.mp4");
    }

    @Test
    void signsCandidateAndDetectionUrlsInDetail() {
        AdminCandidateDetectionRow detection = new AdminCandidateDetectionRow();
        detection.setFrameObjectKey("frames/detection-frame.jpg");
        detection.setCropObjectKey("crops/detection-crop.jpg");
        when(mapper.findById(1L)).thenReturn(candidate());
        when(mapper.findDetections(1L)).thenReturn(List.of(detection));
        stubSignedUrls();

        AdminCandidateDetailResponse result = service.findById(1L);

        assertEquals("https://storage.example/frame", result.frameUrl());
        assertEquals("https://storage.example/crop", result.cropUrl());
        assertEquals("https://storage.example/missing-photo", result.missingPhotoUrl());
        assertEquals("https://storage.example/recording", result.recordingUrl());
        assertEquals("https://storage.example/clip", result.clipUrl());
        assertEquals("READY", result.clipStatus());
        assertEquals("https://storage.example/detection-crop", result.detections().getFirst().cropUrl());
        verify(signer, never()).createGetUrl("frames/detection-frame.jpg");
        assertFalse(result.toString().contains("frames/frame.jpg"));
        assertFalse(result.toString().contains("crops/crop.jpg"));
        assertFalse(result.toString().contains("frames/detection-frame.jpg"));
        assertFalse(result.toString().contains("crops/detection-crop.jpg"));
    }

    @Test
    void doesNotSignBlankOptionalObjectKeys() {
        AdminCandidateRow row = candidate();
        row.setMissingPhotoObjectKey(" ");
        row.setRecordingObjectKey("");
        row.setClipObjectKey(null);
        when(mapper.findById(1L)).thenReturn(row);
        when(mapper.findDetections(1L)).thenReturn(List.of());

        AdminCandidateDetailResponse result = service.findById(1L);

        assertNull(result.missingPhotoUrl());
        assertNull(result.recordingUrl());
        assertNull(result.clipUrl());
        verify(signer, never()).createGetUrl(" ");
        verify(signer, never()).createGetUrl("");
    }

    @Test
    void signsOriginalRecordingWhenClipIsNotReady() {
        AdminCandidateRow row = candidate();
        row.setClipObjectKey(null);
        row.setClipStatus("PENDING");
        when(mapper.findById(1L)).thenReturn(row);
        when(mapper.findDetections(1L)).thenReturn(List.of());
        stubSignedUrls();

        AdminCandidateDetailResponse result = service.findById(1L);

        assertEquals("https://storage.example/recording", result.recordingUrl());
        assertNull(result.clipUrl());
        assertEquals("PENDING", result.clipStatus());
        verify(signer).createGetUrl("recordings/1.mp4");
        verify(signer, never()).createGetUrl("clips/1.mp4");
    }

    private void stubSignedUrls() {
        when(signer.createGetUrl("frames/frame.jpg")).thenReturn("https://storage.example/frame");
        when(signer.createGetUrl("crops/crop.jpg")).thenReturn("https://storage.example/crop");
        when(signer.createGetUrl("cases/1/missing.jpg")).thenReturn("https://storage.example/missing-photo");
        when(signer.createGetUrl("recordings/1.mp4")).thenReturn("https://storage.example/recording");
        when(signer.createGetUrl("clips/1.mp4")).thenReturn("https://storage.example/clip");
        when(signer.createGetUrl("crops/detection-crop.jpg"))
                .thenReturn("https://storage.example/detection-crop");
    }

    private AdminCandidateRow candidate() {
        AdminCandidateRow row = new AdminCandidateRow();
        row.setId(1L);
        row.setSourceType(CandidateSourceType.REALTIME);
        row.setFrameObjectKey("frames/frame.jpg");
        row.setCropObjectKey("crops/crop.jpg");
        row.setMissingPhotoObjectKey("cases/1/missing.jpg");
        row.setRecordingObjectKey("recordings/1.mp4");
        row.setClipObjectKey("clips/1.mp4");
        row.setClipStatus("READY");
        return row;
    }
}
