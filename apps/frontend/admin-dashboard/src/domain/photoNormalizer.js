export const MAX_CASE_PHOTO_BYTES = 10 * 1024 * 1024;
export const MAX_CASE_PHOTO_DIMENSION = 4096;

const NORMALIZED_PHOTO_TARGET_MB = 9;
const JPEG_QUALITY = 0.9;
const HEIC_BRANDS = new Set(["heic", "heix", "hevc", "hevx", "mif1", "msf1"]);
const EXTENSIONS = {
  "image/jpeg": "jpg",
  "image/png": "png",
  "image/webp": "webp"
};

export class PhotoNormalizationError extends Error {
  constructor(message, code = "PHOTO_NORMALIZATION_FAILED", cause) {
    super(message, cause ? { cause } : undefined);
    this.name = "PhotoNormalizationError";
    this.code = code;
  }
}

function unsupportedPhoto() {
  return new PhotoNormalizationError(
    "JPEG, PNG, WebP, HEIC, HEIF 이미지만 업로드할 수 있습니다.",
    "UNSUPPORTED_PHOTO_TYPE"
  );
}

async function readArrayBuffer(blob) {
  if (typeof blob.arrayBuffer === "function") return blob.arrayBuffer();

  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsArrayBuffer(blob);
  });
}

async function detectPhotoType(file) {
  const bytes = new Uint8Array(await readArrayBuffer(file.slice(0, 12)));

  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) {
    return "image/jpeg";
  }

  const png = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (bytes.length >= png.length && png.every((value, index) => bytes[index] === value)) {
    return "image/png";
  }

  const ascii = (from, to) => String.fromCharCode(...bytes.slice(from, to));
  if (bytes.length >= 12 && ascii(0, 4) === "RIFF" && ascii(8, 12) === "WEBP") {
    return "image/webp";
  }

  if (bytes.length >= 12 && ascii(4, 8) === "ftyp" && HEIC_BRANDS.has(ascii(8, 12))) {
    return "image/heic";
  }

  throw unsupportedPhoto();
}

function replaceExtension(name, extension) {
  const baseName = String(name || "case-photo").replace(/\.[^.]+$/, "") || "case-photo";
  return `${baseName}.${extension}`;
}

function asFile(blob, originalFile, contentType) {
  const extension = EXTENSIONS[contentType];
  return new File([blob], replaceExtension(originalFile.name, extension), {
    type: contentType,
    lastModified: originalFile.lastModified || Date.now()
  });
}

async function defaultReadDimensions(file) {
  if (typeof createImageBitmap === "function") {
    const bitmap = await createImageBitmap(file);
    try {
      return { width: bitmap.width, height: bitmap.height };
    } finally {
      bitmap.close?.();
    }
  }

  if (typeof Image !== "function" || typeof URL?.createObjectURL !== "function") {
    throw new Error("This browser cannot inspect image dimensions.");
  }

  const objectUrl = URL.createObjectURL(file);
  try {
    return await new Promise((resolve, reject) => {
      const image = new Image();
      image.onload = () => resolve({
        width: image.naturalWidth || image.width,
        height: image.naturalHeight || image.height
      });
      image.onerror = () => reject(new Error("Image dimensions could not be read."));
      image.src = objectUrl;
    });
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

async function defaultConvertHeic(file) {
  const { heicTo } = await import("heic-to/csp");
  return heicTo({ blob: file, type: "image/jpeg", quality: JPEG_QUALITY });
}

async function defaultCompress(file, signal) {
  const { default: imageCompression } = await import("browser-image-compression");
  return imageCompression(file, {
    maxSizeMB: NORMALIZED_PHOTO_TARGET_MB,
    maxWidthOrHeight: MAX_CASE_PHOTO_DIMENSION,
    // Keep the application self-contained instead of loading the worker bundle from a CDN.
    useWebWorker: false,
    fileType: "image/jpeg",
    initialQuality: JPEG_QUALITY,
    signal
  });
}

export async function normalizeCasePhoto(file, options = {}) {
  if (!(file instanceof Blob) || file.size === 0) {
    throw new PhotoNormalizationError("사진 파일을 선택해 주세요.", "EMPTY_PHOTO");
  }

  const {
    convertHeic = defaultConvertHeic,
    compress = defaultCompress,
    readDimensions = defaultReadDimensions,
    signal
  } = options;

  let detectedType;
  try {
    detectedType = await detectPhotoType(file);
  } catch (error) {
    if (error instanceof PhotoNormalizationError) throw error;
    throw new PhotoNormalizationError(
      "사진 형식을 확인하지 못했습니다. 다른 사진을 선택해 주세요.",
      "PHOTO_READ_FAILED",
      error
    );
  }

  let normalizedFile;
  if (detectedType === "image/heic") {
    try {
      const jpegBlob = await convertHeic(file);
      normalizedFile = asFile(jpegBlob, file, "image/jpeg");
    } catch (error) {
      throw new PhotoNormalizationError(
        "HEIC/HEIF 사진을 변환하지 못했습니다. 다른 사진을 선택해 주세요.",
        "HEIC_CONVERSION_FAILED",
        error
      );
    }
  } else {
    normalizedFile = file.type.toLowerCase() === detectedType
      ? file
      : asFile(file, file, detectedType);
  }

  let dimensions;
  try {
    dimensions = await readDimensions(normalizedFile);
  } catch (error) {
    throw new PhotoNormalizationError(
      "사진을 읽지 못했습니다. 손상되지 않은 사진을 선택해 주세요.",
      "PHOTO_DECODE_FAILED",
      error
    );
  }

  const needsCompression = detectedType === "image/heic"
    || normalizedFile.size > MAX_CASE_PHOTO_BYTES
    || Math.max(dimensions.width, dimensions.height) > MAX_CASE_PHOTO_DIMENSION;

  if (needsCompression) {
    try {
      const compressed = await compress(normalizedFile, signal);
      normalizedFile = asFile(compressed, file, "image/jpeg");
    } catch (error) {
      if (error?.name === "AbortError") throw error;
      throw new PhotoNormalizationError(
        "사진 크기를 줄이지 못했습니다. 다른 사진을 선택해 주세요.",
        "PHOTO_COMPRESSION_FAILED",
        error
      );
    }
  }

  if (normalizedFile.size > MAX_CASE_PHOTO_BYTES) {
    throw new PhotoNormalizationError(
      "사진은 변환 후 10MB 이하여야 합니다.",
      "PHOTO_TOO_LARGE"
    );
  }

  return normalizedFile;
}
