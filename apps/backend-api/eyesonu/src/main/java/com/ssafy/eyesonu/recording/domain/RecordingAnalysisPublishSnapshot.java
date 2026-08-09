package com.ssafy.eyesonu.recording.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RecordingAnalysisPublishSnapshot {

    private Long jobId;
    private Long caseId;
    private Long recordingId;
    private Long cameraId;
    private String cameraCode;
    private String cameraName;
    private String recordingObjectKey;
    private Long recordingFileSizeBytes;
    private Instant recordingStart;
    private Instant recordingEnd;
    private String prompt;
    private String exclusionPrompt;
    private Instant searchStart;
    private Instant searchEnd;
    private String searchArea;
    private int attempt;
}
