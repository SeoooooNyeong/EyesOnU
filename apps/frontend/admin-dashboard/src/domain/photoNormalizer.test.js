import { describe, expect, it, vi } from "vitest";
import {
  MAX_CASE_PHOTO_BYTES,
  normalizeCasePhoto,
  PhotoNormalizationError
} from "./photoNormalizer";

const dimensions = { width: 1200, height: 800 };
const jpegBytes = new Uint8Array([0xff, 0xd8, 0xff, 0x01]);
const pngBytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const heicBytes = new Uint8Array([
  0x00, 0x00, 0x00, 0x18,
  0x66, 0x74, 0x79, 0x70,
  0x68, 0x65, 0x69, 0x63
]);

function file(bytes, name, type = "") {
  const parts = Array.isArray(bytes) ? bytes : [bytes];
  return new File(parts, name, { type, lastModified: 1234 });
}

describe("normalizeCasePhoto", () => {
  it("keeps a valid supported photo unchanged", async () => {
    const original = file(pngBytes, "person.png", "image/png");
    const compress = vi.fn();

    const result = await normalizeCasePhoto(original, {
      readDimensions: vi.fn().mockResolvedValue(dimensions),
      compress
    });

    expect(result).toBe(original);
    expect(compress).not.toHaveBeenCalled();
  });

  it.each(["image/jpg", ""])("canonicalizes a JPEG reported as %s", async (reportedType) => {
    const original = file(jpegBytes, "camera.jpg", reportedType);

    const result = await normalizeCasePhoto(original, {
      readDimensions: vi.fn().mockResolvedValue(dimensions)
    });

    expect(result).toBeInstanceOf(File);
    expect(result.type).toBe("image/jpeg");
    expect(result.name).toBe("camera.jpg");
  });

  it("converts HEIC to a compressed JPEG", async () => {
    const original = file(heicBytes, "iphone.heic", "image/heic");
    const convertHeic = vi.fn().mockResolvedValue(new Blob([jpegBytes], { type: "image/jpeg" }));
    const compress = vi.fn().mockResolvedValue(new Blob([jpegBytes], { type: "image/jpeg" }));

    const result = await normalizeCasePhoto(original, {
      convertHeic,
      compress,
      readDimensions: vi.fn().mockResolvedValue(dimensions)
    });

    expect(convertHeic).toHaveBeenCalledWith(original);
    expect(compress).toHaveBeenCalledOnce();
    expect(result.name).toBe("iphone.jpg");
    expect(result.type).toBe("image/jpeg");
  });

  it("compresses an oversized camera photo and returns a server-compatible JPEG", async () => {
    const oversized = file(
      [jpegBytes, new Uint8Array(MAX_CASE_PHOTO_BYTES)],
      "large-camera.jpg",
      "image/jpeg"
    );
    const compressed = new Blob([jpegBytes], { type: "image/jpeg" });
    const compress = vi.fn().mockResolvedValue(compressed);

    const result = await normalizeCasePhoto(oversized, {
      compress,
      readDimensions: vi.fn().mockResolvedValue({ width: 5000, height: 3000 })
    });

    expect(compress).toHaveBeenCalledOnce();
    expect(result.size).toBe(compressed.size);
    expect(result.type).toBe("image/jpeg");
  });

  it("compresses a high-resolution photo even when its file size is below the limit", async () => {
    const original = file(jpegBytes, "high-resolution.jpg", "image/jpeg");
    const compress = vi.fn().mockResolvedValue(new Blob([jpegBytes], { type: "image/jpeg" }));

    await normalizeCasePhoto(original, {
      compress,
      readDimensions: vi.fn().mockResolvedValue({ width: 5000, height: 2800 })
    });

    expect(compress).toHaveBeenCalledOnce();
  });

  it("rejects an unsupported file by its actual signature", async () => {
    const gif = file(new Uint8Array([0x47, 0x49, 0x46, 0x38]), "person.jpg", "image/jpeg");

    await expect(normalizeCasePhoto(gif, {
      readDimensions: vi.fn().mockResolvedValue(dimensions)
    })).rejects.toMatchObject({
      name: "PhotoNormalizationError",
      code: "UNSUPPORTED_PHOTO_TYPE"
    });
  });

  it("reports conversion failures with a user-facing error", async () => {
    const original = file(heicBytes, "broken.heic", "image/heic");

    await expect(normalizeCasePhoto(original, {
      convertHeic: vi.fn().mockRejectedValue(new Error("decode failed")),
      readDimensions: vi.fn().mockResolvedValue(dimensions)
    })).rejects.toEqual(expect.objectContaining({
      name: "PhotoNormalizationError",
      code: "HEIC_CONVERSION_FAILED"
    }));
  });

  it("rejects a compressor result that remains above the backend limit", async () => {
    const original = file(
      [jpegBytes, new Uint8Array(MAX_CASE_PHOTO_BYTES)],
      "large.jpg",
      "image/jpeg"
    );
    const stillLarge = new Blob([new Uint8Array(MAX_CASE_PHOTO_BYTES + 1)], { type: "image/jpeg" });

    await expect(normalizeCasePhoto(original, {
      compress: vi.fn().mockResolvedValue(stillLarge),
      readDimensions: vi.fn().mockResolvedValue(dimensions)
    })).rejects.toEqual(expect.objectContaining({
      name: "PhotoNormalizationError",
      code: "PHOTO_TOO_LARGE"
    }));
  });

  it("exports a typed normalization error", () => {
    expect(new PhotoNormalizationError("test")).toBeInstanceOf(Error);
  });
});
