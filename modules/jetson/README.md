# Jetson AI 모듈

Jetson에서 RTSP 카메라 영상을 받아 사람을 검출(YOLO)하고, 성별/상의색/하의색/소매
속성(RTEYES 모델)을 인식해 서버가 등록한 인상착의(사건)와 매칭한 뒤, 매칭된 사람의
좌표와 증거 사진을 후보 이벤트로 백엔드에 등록하는 엣지 디바이스 서비스.

## 구조

```
modules/jetson/
├── scripts/
│   ├── jetson_rteyes_sender.py  # 메인 — 카메라 → 검출 → 속성 → 매칭 → 서버 전송
│   ├── capture_core.py          # 카메라 수신 / 모델 로딩 / 속성 트래킹 / 전신 게이트
│   ├── onnx_yolo.py             # onnxruntime만으로 돌리는 YOLO11 사람 검출
│   ├── server_config.py         # 백엔드 연동 설정 (.env에서 비밀값 로드)
│   ├── server_link.py           # 검색 대상 동기화 + 탐지 결과 전송
│   ├── mq_listener.py           # RabbitMQ 알림 소비자 (없어도 폴링으로 동작)
│   ├── prepare.py               # TensorRT 엔진 빌드 + fp16 검증 (기기당 최초 1회)
│   └── run_eyesonu.sh           # 실행 래퍼 (중복 실행 차단)
├── models/                      # 모델 가중치 자리 (git 미포함 — 아래 참고)
├── pylibs/                      # 프로젝트 전용 의존성 (git 미포함 — PYLIBS.md 참고)
├── requirements.txt
├── PYLIBS.md                    # pylibs 준비 방법
└── .env.example
```

## 준비

1. **모델**: `models/`에 `yolo11s.onnx`, `rteyes.onnx`를 넣는다 (별도 전달).
2. **의존성**: [PYLIBS.md](PYLIBS.md) 참고 — onnxruntime-gpu는 Jetson 전용 wheel 필요.
3. **설정**:
   ```bash
   cp .env.example .env
   chmod 600 .env
   # RTSP/MQ 계정, API_BASE 채우기. 디바이스 인증키는 devicekey.txt에 한 줄.
   ```
4. **TensorRT 엔진** (기기당 최초 1회):
   ```bash
   python3 scripts/prepare.py
   ```

## 실행

```bash
./scripts/run_eyesonu.sh
# 또는
python3 scripts/jetson_rteyes_sender.py
```

검색 대상(사건)은 전부 서버가 준다 — 사건이 없으면 감시만 하고, RabbitMQ가 막혀
있어도 5초 주기 폴링(ETag)으로 동기화된다.
