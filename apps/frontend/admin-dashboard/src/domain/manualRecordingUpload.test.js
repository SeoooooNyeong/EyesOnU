import { describe, expect, it, vi } from "vitest";
import {
  buildManualRecordingPeriod,
  kstDateTimeInputToIso,
  MAX_MANUAL_RECORDING_BYTES,
  readManualRecordingDuration,
  toKstDateTimeInput,
  validateManualRecordingDuration,
  validateManualRecordingFile
} from "./manualRecordingUpload";

const mp4 = (size = 12) => new File([new Uint8Array(size)], "recording.mp4", { type: "video/mp4" });

describe("manualRecordingUpload domain", () => {
  it("converts KST datetime-local values without depending on the runtime timezone", () => {
    expect(kstDateTimeInputToIso("2026-08-08T15:04:05")).toBe("2026-08-08T06:04:05.000Z");
    expect(toKstDateTimeInput("2026-08-08T06:04:05Z")).toBe("2026-08-08T15:04:05");
    expect(() => kstDateTimeInputToIso("2026-02-30T10:00:00")).toThrow("올바르지 않습니다");
  });

  it("builds a past recording period from the KST start and MP4 duration", () => {
    expect(buildManualRecordingPeriod(
      "2026-08-08T15:04:05",
      30.125,
      new Date("2026-08-08T06:05:00Z")
    )).toEqual({
      startTime: "2026-08-08T06:04:05.000Z",
      endTime: "2026-08-08T06:04:35.125Z",
      startKst: "2026-08-08T15:04:05",
      endKst: "2026-08-08T15:04:35"
    });
    expect(() => buildManualRecordingPeriod(
      "2026-08-08T15:04:05",
      60,
      new Date("2026-08-08T06:04:30Z")
    )).toThrow("미래일 수 없습니다");
  });

  it("accepts common browser MP4 MIME values with a .mp4 extension within 100 MiB", () => {
    expect(validateManualRecordingFile(mp4())).toBeInstanceOf(File);
    expect(validateManualRecordingFile(new File(["mp4"], "recording.MP4"))).toBeInstanceOf(File);
    expect(validateManualRecordingFile(
      new File(["mp4"], "recording.mp4", { type: "application/mp4" })
    )).toBeInstanceOf(File);
    expect(() => validateManualRecordingFile(new File([], "empty.mp4", { type: "video/mp4" }))).toThrow("선택");
    expect(() => validateManualRecordingFile(new File(["x"], "clip.mov", { type: "video/mp4" }))).toThrow(".mp4");
    expect(() => validateManualRecordingFile(new File(["x"], "clip.mp4", { type: "video/quicktime" }))).toThrow("MIME");
    const oversized = mp4();
    Object.defineProperty(oversized, "size", { value: MAX_MANUAL_RECORDING_BYTES + 1 });
    expect(() => validateManualRecordingFile(oversized)).toThrow("이하여야");
    expect(() => validateManualRecordingFile(
      new File([new Uint8Array(1)], "large.mp4", { type: "video/mp4" }),
      0
    )).toThrow("이하여야");
  });

  it("requires a finite duration greater than zero and no longer than 60 seconds", () => {
    expect(validateManualRecordingDuration(60)).toBe(60);
    expect(() => validateManualRecordingDuration(0)).toThrow("확인할 수 없습니다");
    expect(() => validateManualRecordingDuration(60.001)).toThrow("60초 이하");
  });

  it("reads metadata duration and always releases the object URL", async () => {
    const video = { duration: 30.25, load: vi.fn(), removeAttribute: vi.fn() };
    const revokeObjectURL = vi.fn();
    const promise = readManualRecordingDuration(mp4(), {
      createObjectURL: () => "blob:recording",
      revokeObjectURL,
      createVideo: () => video
    });
    video.onloadedmetadata();

    await expect(promise).resolves.toBe(30.25);
    expect(video.src).toBe("blob:recording");
    expect(video.removeAttribute).toHaveBeenCalledWith("src");
    expect(revokeObjectURL).toHaveBeenCalledWith("blob:recording");
  });
});
