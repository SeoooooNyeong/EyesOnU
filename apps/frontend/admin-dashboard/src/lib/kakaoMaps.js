let sdkPromise;

export function loadKakaoMapsSdk(appKey) {
  if (window.kakao?.maps) return Promise.resolve(window.kakao);
  if (!appKey) return Promise.reject(new Error("Kakao Maps app key is missing."));
  if (sdkPromise) return sdkPromise;

  sdkPromise = new Promise((resolve, reject) => {
    const finishLoading = () => {
      if (!window.kakao?.maps?.load) {
        reject(new Error("Kakao Maps SDK did not initialize."));
        return;
      }
      window.kakao.maps.load(() => resolve(window.kakao));
    };
    const failLoading = () => {
      sdkPromise = undefined;
      reject(new Error("Kakao Maps SDK failed to load."));
    };
    const existing = document.querySelector("script[data-kakao-map-sdk]");

    if (existing) {
      existing.addEventListener("load", finishLoading, { once: true });
      existing.addEventListener("error", failLoading, { once: true });
      return;
    }

    const script = document.createElement("script");
    script.dataset.kakaoMapSdk = "true";
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(appKey)}&autoload=false`;
    script.addEventListener("load", finishLoading, { once: true });
    script.addEventListener("error", failLoading, { once: true });
    document.head.appendChild(script);
  });

  return sdkPromise;
}
