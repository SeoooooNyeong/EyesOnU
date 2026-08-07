# pylibs 준비 방법

이 코드는 `pylibs/`를 시스템 site-packages가 아니라 **프로젝트 전용 의존성 폴더**로 쓴다
(`capture_core.py`가 시작할 때 `sys.path`에 직접 끼워 넣는다). 이렇게 분리하는 이유:

- JetPack이 이미 깔아둔 `cv2`/`numpy`/`requests`는 CUDA에 맞춰 빌드된 버전이라
  pip로 덮어쓰면 깨질 수 있다 — 그래서 시스템 site-packages를 건드리지 않는다.
- `onnxruntime-gpu`는 Jetson(aarch64/Tegra) 전용 wheel이 필요해서 PyPI 표준
  패키지로는 GPU 프로바이더가 안 잡힌다.

## 1) 순수 파이썬 패키지 (pika, flatbuffers, coloredlogs, humanfriendly)

`requirements.txt`에 있는 나머지는 표준 PyPI 패키지라 그대로 받으면 된다.
`pylibs/` 폴더로 바로 설치(`--target`):

```bash
cd ~/project/JetsonAI
pip3 install --target=pylibs -r requirements.txt --no-deps pika flatbuffers coloredlogs humanfriendly
```

(`onnxruntime-gpu`는 requirements.txt에 같이 있지만 위 명령으로는 못 받는다 — 2번 참고)

## 2) onnxruntime-gpu (Jetson 전용 wheel)

PyPI의 `onnxruntime-gpu`는 x86_64/일반 CUDA용이라 Jetson에서 GPU 프로바이더가 안 잡히고
조용히 CPU로만 돈다. JetPack 버전에 맞는 aarch64 wheel을 따로 받아야 한다.

1. 이 기기의 JetPack 버전 확인:
   ```bash
   dpkg-query --show -f='${Version}\n' nvidia-l4t-core
   ```
2. NVIDIA Jetson Zoo(https://elinux.org/Jetson_Zoo#ONNX_Runtime) 또는 JetPack 버전에
   맞는 배포처에서 그 버전용 `onnxruntime_gpu-*.whl` 파일을 받는다.
3. `pylibs/`에 설치:
   ```bash
   pip3 install --target=pylibs onnxruntime_gpu-1.24.0-*.whl
   ```

## 3) 확인

```bash
cd ~/project/JetsonAI
PYTHONPATH=pylibs python3 -c "import onnxruntime as ort; print(ort.get_available_providers())"
```

`CUDAExecutionProvider`나 `TensorrtExecutionProvider`가 목록에 없으면 wheel이
JetPack 버전과 안 맞는다는 뜻이다 — CPU로만 돌면 수십 배 느려진다
(`capture_core.py`의 `warn_if_cpu`가 이 상황을 실행 시 경고로 잡아준다).

## 4) TensorRT 엔진 굽기 (최초 1회)

```bash
python3 scripts/prepare.py
```

fp16과 fp32 결과를 비교해 안전하면 `models/provider_verdict.json`에 판정을 남긴다.
`models/trt_cache/`에 기기별 엔진이 쌓이는데, 배치 크기(YOLO 1/2/4, RTEYES 1/2/4/8)마다
새로 빌드해서 처음 한 번은 몇 분~십수 분 걸릴 수 있다.
