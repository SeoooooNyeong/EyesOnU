# Recording analysis storage URL contract

The recording AI Worker downloads the source recording through a presigned GET
URL, sends analysis details to the backend API, and uploads the generated frame
and crop images directly to MinIO through presigned PUT URLs. The backend issues
those URLs after the Worker claims the analysis job. The Worker does not need
MinIO credentials.

## 1. Claim the job

```http
POST /api/v1/internal/recording-analysis-jobs/{jobId}/claim
X-Worker-Key: <worker-key>
```

The response contains the current `attempt`. The Worker must use that attempt
when uploading images and submitting the result.

## 2. Read the analysis target

The Worker reads the recording source and appearance target through the backend
after claiming the job:

```http
GET /api/v1/internal/recording-analysis-jobs/{jobId}/target
X-Worker-Key: <worker-key>
```

The response includes the recording object key and a presigned download URL,
the stored file size and content type, canonical appearance prompts, the
requested search range, recording range, effective analysis range, search area,
camera information, and the current attempt. The Worker must use this response
instead of receiving appearance prompts through RabbitMQ.

```json
{
  "jobId": 42,
  "caseId": 7,
  "recordingId": 15,
  "cameraId": 3,
  "cameraCode": "CAM-003",
  "cameraName": "Front",
  "recordingObjectKey": "recordings/CAM-003/2026/08/03/video.mp4",
  "recordingDownloadUrl": "https://storage.example.com/eyesonu-media/recordings/CAM-003/2026/08/03/video.mp4?...",
  "recordingDownloadUrlExpiresInSeconds": 900,
  "recordingFileSizeBytes": 20971520,
  "recordingContentType": "video/mp4",
  "recordingStart": "2026-08-02T23:45:00Z",
  "recordingEnd": "2026-08-03T00:20:00Z",
  "prompt": "a man wearing a black short sleeve top and black pants",
  "exclusionPrompt": null,
  "searchStart": "2026-08-03T00:00:00Z",
  "searchEnd": "2026-08-03T00:30:00Z",
  "analysisStart": "2026-08-03T00:00:00Z",
  "analysisEnd": "2026-08-03T00:20:00Z",
  "searchArea": "front gate",
  "attempt": 1
}
```

`recordingObjectKey` remains in the response for compatibility and job
traceability. The default download URL lifetime is 900 seconds (15 minutes), as
reported by `recordingDownloadUrlExpiresInSeconds`. Current Worker-bound
recordings are at most approximately 20 MB, so the Worker must download the
entire file to a local temporary file immediately after reading the target,
rather than stream from the URL throughout analysis.

If the URL expires or the download must be retried, the Worker calls this
`/target` endpoint again while it still owns the running job to receive a new
URL. `X-Worker-Key` is only for backend API requests and must not be forwarded
to the storage GET request. The full presigned URL, including its query string,
must not be written to logs.

All time ranges use half-open interval semantics (`[start, end)`). The effective
range is calculated as `analysisStart = max(searchStart, recordingStart)` and
`analysisEnd = min(searchEnd, recordingEnd)`. The Worker starts decoding at the
offset `analysisStart - recordingStart` and stops at `analysisEnd`.

## 3. Request image upload URLs

The Worker must request upload URLs in chunks of at most 100 candidates. A
recording analysis result may contain up to 1,000 candidates, so the Worker can
make multiple requests and combine the responses before submitting the result.
Each request must contain unique `trackId` values, and the Worker must not
include the same track in more than one chunk.

```http
POST /api/v1/internal/recording-analysis-jobs/{jobId}/upload-urls
X-Worker-Key: <worker-key>
Content-Type: application/json
```

```json
{
  "candidates": [
    {
      "trackId": "track-17",
      "frameContentType": "image/jpeg",
      "cropContentType": "image/png"
    }
  ]
}
```

Only the Worker that currently owns the job lease can request URLs. The
backend derives the attempt from the job and creates the object keys. The
response has one frame URL and one crop URL per track:

```json
{
  "attempt": 1,
  "candidates": [
    {
      "trackId": "track-17",
      "frame": {
        "objectKey": "analysis/analysis-42/attempt-1/frames/<sha256>.jpg",
        "uploadUrl": "https://minio.example/...",
        "contentType": "image/jpeg"
      },
      "crop": {
        "objectKey": "analysis/analysis-42/attempt-1/crops/<sha256>.png",
        "uploadUrl": "https://minio.example/...",
        "contentType": "image/png"
      }
    }
  ],
  "expiresInSeconds": 900
}
```

The Worker uploads only the image bytes to each URL. It must use the returned
`contentType` and retry the URL request if the URL expires.

## 4. Submit the analysis details

After every image upload succeeds, submit the result using the returned object
keys:

```http
POST /api/v1/internal/recording-analysis-jobs/{jobId}/result
X-Worker-Key: <worker-key>
Content-Type: application/json
```

The result request contains `detectedAt`, `similarity`, `boundingBox`,
`frameObjectKey`, and `cropObjectKey`. The backend verifies that both objects
belong to the current job attempt and validates their size, MIME type, and
JPEG/PNG signature before persisting the detailed candidate data.
