<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { loadKakaoMapsSdk } from "../../lib/kakaoMaps";

const props = defineProps({
  points: { type: Array, default: () => [] },
  activeIndex: { type: Number, default: -1 }
});

const emit = defineEmits(["select"]);
const mapContainer = ref(null);
const mapState = ref("loading");
const mapMessage = ref("지도를 불러오는 중입니다.");
const appKey = String(import.meta.env.VITE_KAKAO_MAP_APP_KEY || "").trim();

let kakao;
let map;
let polyline;
let overlays = [];
let disposed = false;

const validPoints = () => props.points
  .map((point, index) => ({
    point,
    index,
    latitude: Number(point.latitude),
    longitude: Number(point.longitude)
  }))
  .filter(({ latitude, longitude }) => Number.isFinite(latitude) && Number.isFinite(longitude));

function clearMapObjects() {
  overlays.forEach(({ overlay, element, clickHandler }) => {
    element.removeEventListener("click", clickHandler);
    overlay.setMap(null);
  });
  overlays = [];
  if (polyline) polyline.setMap(null);
  polyline = undefined;
}

function groupedPoints(points) {
  const groups = new Map();
  points.forEach((entry) => {
    const key = `${entry.latitude.toFixed(7)}:${entry.longitude.toFixed(7)}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(entry);
  });
  return [...groups.values()];
}

function updateActiveMarker() {
  overlays.forEach(({ element, indices }) => {
    element.classList.toggle("active", indices.includes(props.activeIndex));
  });
}

function drawRoute() {
  if (!map || !kakao) return;
  clearMapObjects();
  const points = validPoints();
  if (!points.length) {
    mapState.value = "empty";
    mapMessage.value = "표시할 카메라 좌표가 없습니다.";
    return;
  }

  const path = points.map(({ latitude, longitude }) => new kakao.maps.LatLng(latitude, longitude));
  const bounds = new kakao.maps.LatLngBounds();
  path.forEach((position) => bounds.extend(position));

  if (path.length > 1) {
    polyline = new kakao.maps.Polyline({
      map,
      path,
      strokeWeight: 4,
      strokeColor: "#2f7b9b",
      strokeOpacity: 0.9,
      strokeStyle: "solid"
    });
    map.setBounds(bounds, 56, 56, 56, 56);
  } else {
    map.setCenter(path[0]);
    map.setLevel(4);
  }

  groupedPoints(points).forEach((group) => {
    const indices = group.map(({ index }) => index);
    const element = document.createElement("button");
    element.type = "button";
    element.className = "route-coordinate-marker";
    const markerTitle = `${indices.map((index) => `${index + 1}번`).join(", ")} 관측 지점`;
    element.setAttribute("aria-label", markerTitle);
    element.title = markerTitle;
    const markerImage = document.createElement("img");
    markerImage.src = "/cctv-marker.webp";
    markerImage.alt = "";
    markerImage.draggable = false;
    element.appendChild(markerImage);
    const clickHandler = () => emit("select", indices[0]);
    element.addEventListener("click", clickHandler);
    const overlay = new kakao.maps.CustomOverlay({
      map,
      position: new kakao.maps.LatLng(group[0].latitude, group[0].longitude),
      content: element,
      yAnchor: 0.5,
      zIndex: 3
    });
    overlays.push({ overlay, element, indices, clickHandler });
  });

  updateActiveMarker();
  mapState.value = "ready";
  mapMessage.value = "";
}

async function initializeMap() {
  if (!appKey) {
    mapState.value = "missing-key";
    mapMessage.value = "카카오맵 키가 설정되지 않아 지도 대신 동선 목록만 표시합니다.";
    return;
  }
  if (!validPoints().length) {
    mapState.value = "empty";
    mapMessage.value = "표시할 카메라 좌표가 없습니다.";
    return;
  }

  try {
    kakao = await loadKakaoMapsSdk(appKey);
    await nextTick();
    if (disposed || !mapContainer.value) return;
    const first = validPoints()[0];
    map = new kakao.maps.Map(mapContainer.value, {
      center: new kakao.maps.LatLng(first.latitude, first.longitude),
      level: 5
    });
    drawRoute();
  } catch {
    if (disposed) return;
    mapState.value = "error";
    mapMessage.value = "카카오맵을 불러오지 못했습니다. 동선 목록에서 위치를 확인해 주세요.";
  }
}

watch(() => props.points, () => {
  if (map) drawRoute();
  else if (mapState.value === "empty" && validPoints().length) initializeMap();
}, { deep: true });

watch(() => props.activeIndex, (index) => {
  updateActiveMarker();
  const entry = validPoints().find((point) => point.index === index);
  if (map && kakao && entry) {
    map.panTo(new kakao.maps.LatLng(entry.latitude, entry.longitude));
  }
});

onMounted(initializeMap);
onBeforeUnmount(() => {
  disposed = true;
  clearMapObjects();
});
</script>

<template>
  <div class="map-panel route-map" :class="`map-${mapState}`">
    <div ref="mapContainer" class="route-map-canvas" aria-label="확정 후보 추정 동선 지도"></div>
    <div v-if="mapMessage" class="route-map-status" role="status">
      <strong>{{ mapMessage }}</strong>
    </div>
  </div>
</template>
