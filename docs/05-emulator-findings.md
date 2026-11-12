# 에뮬레이터 검증 결과

- 검증일: 2026-10-21
- 환경: Pleos Connect Emulator v2.1.0 (Android 14, API 34, arm64), Vehicle SDK 2.3.2, macOS Apple Silicon
- 방법: CarStage Lab 진단(`--es probe all`)을 실행하고 `CarStageLab` 로그를 수집했습니다.

## 결론

**서드파티 앱의 마이크 사용은 가능합니다.** 노래방 기획의 가장 큰 전제가 에뮬레이터에서 확인되었습니다. 차량 제어는 API와 권한이 모두 열려 있지만, 에뮬레이터는 일부 쓰기만 실제 값에 반영합니다. 조명 연출이 실제로 보이는지는 실차에서 확인해야 합니다.

## 오디오

| 항목 | 결과 |
|---|---|
| 마이크 녹음 | MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION, UNPROCESSED, CAMCORDER, VOICE_PERFORMANCE 6개 소스 모두 48kHz로 녹음 가능, 음소거되지 않음 |
| 입력 장치 | 내장 마이크 2개(Built-In Mic, Back Mic), BUS 입력 2개, FM 튜너, REMOTE_SUBMIX, Echo-Reference Mic |
| 마이크 배열 | `getMicrophones()` 기준 9개 (builtin_mic1~4 포함) |
| 앱용 오디오 효과 | AEC, NS, AGC 모두 사용 불가 |
| 에코 레퍼런스 직접 녹음 | 거부됨 (`startRecording` 실패, 시스템 권한 필요) |
| 에코 제거 테스트 | 앱이 재생한 음악이 마이크로 다시 들어오지 않아 판단 불가 (재생 전후 차이 0.2~0.8dB) |
| 무음 시 레벨 | -67 ~ -76 dBFS. 2026-10-22에 다시 확인해 보니 이 값은 호스트 소리가 들어오지 않을 때 에뮬레이터가 만드는 잡음 수준과 같습니다. 이때 실제로 호스트 마이크가 연결되어 있었는지는 확실하지 않습니다 |

**해석**
- 차량용 플랫폼에는 Echo-Reference 입력이 있어서, 실차에서는 오디오 HAL 수준의 에코 제거가 동작할 가능성이 있습니다. 하지만 앱이 직접 에코 레퍼런스를 받아 음악과 목소리를 분리하는 방법은 쓸 수 없습니다.
- 따라서 실차에서 에코 제거가 동작하지 않을 경우를 대비해, 목소리만 분리하지 않아도 되는 "흥 점수"(전체 성량, 박수, 박자 반응)를 기본으로 설계하는 편이 안전합니다.

## 차량

| 기능 | 영역 | 접근 | 쓰기 결과 |
|---|---|---|---|
| 기어 | 전체 | 읽기 (`CAR_DRIVE` 권한 필요) | 현재 `GEAR_PARK` |
| 좌석 착석 | 1열 좌·우, 2열 좌·중·우 | 읽기 | SDK 기본값은 1열 우와 2열 전체가 true, 1열 좌가 false입니다. 이 값은 Virtual Console·표준 속성과 반대이므로(아래 "착석 감지 확인", 10-24) 실제로는 운전석만 앉은 상태로 보입니다 |
| 앰비언트 스위치 | 좌석 전체 묶음 1개 | 읽기·쓰기 (AUTOMATIC/ON/OFF) | **반영됨** (OFF→ON) |
| 앰비언트 밝기 | 좌석 전체 묶음 1개 | 읽기·쓰기 (0~100) | 응답은 성공, 값은 그대로 |
| 앰비언트 색 | 좌석 전체 묶음 1개 | 읽기·쓰기 | 응답은 성공, 값은 그대로 (초기값 `0x00FFFFFF`) |
| 드라이브 모드 연동 색 | - | 지원 안 함 | - |
| 독서등 | 1열 좌·우, 2열 좌·우 | 읽기·쓰기 (ON/OFF) | 응답은 성공, 값은 그대로 |
| 실내등 | 전체 | 읽기·쓰기 (ON/OFF) | 쓰기 시험 안 함 |
| 공조 전원 | 좌석 전체 묶음 1개 | 읽기·쓰기 | 응답은 성공, 값은 그대로 (꺼짐 유지) |
| 풍량 | 1열 묶음 | 읽기·쓰기 (1~8) | 실패: NOT_AVAILABLE (공조 전원 꺼짐) |
| 온도 | 1열 좌·우 각각 | 읽기·쓰기 (15~30℃) | 쓰기는 성공 응답만 오고 Virtual Console 값은 그대로(2026-10-25, 아래 "승자 쪽 온도 확인") |
| 듀얼 공조 | 1열 묶음 | 읽기·쓰기 | 현재 꺼짐 |
| 시트 통풍 | 1열 좌·우 각각 | 읽기·쓰기 (0~3) | **반영됨** (0→3) |

- 쓰기 응답 지연은 1~25ms였습니다. 150ms 간격으로 색을 계속 바꿔도 실패가 없었습니다.

**해석**
- 앰비언트 조명은 SDK가 좌석별 영역(`SeatArea`)을 지원하지만, 이 에뮬레이터 프로필은 실내 전체를 영역 하나로 다룹니다. 실차도 같다면 앰비언트 조명으로는 좌우 연출을 할 수 없습니다.
- 좌우 연출에 쓸 수 있는 기능은 **독서등(좌석별), 온도(좌우), 시트 통풍(좌우)**이고, 여기에 스피커 좌우 패닝과 화면 연출을 더합니다.
- 에뮬레이터는 색·밝기·독서등 쓰기를 실제 값에 반영하지 않습니다. 그래서 앱이 보낸 명령을 그대로 그려 주는 "실내 시뮬레이터" 화면이 필요합니다.
    - 2026-10-23 다시 확인: Virtual Console의 상태에서는 앰비언트 색·밝기가 앱이 쓴 값으로 바뀌어 보였습니다. 아래 "강제 종료 후 복원 확인"을 참고합니다.

## 추가 확인 (2026-10-22)

| 항목 | 결과 |
|---|---|
| 호스트 마이크 신호 | 조용한 방에서 맥북 내장 마이크로 5초 녹음: 평균 -43.8 dBFS, 최대 -24.8 dBFS. 실제 소리가 들어옵니다 |
| 호스트 마이크가 꺼진 경우 | 녹음은 되지만 에뮬레이터가 만든 미세한 잡음만 들어옵니다(값 14종, 최대 ±7 LSB, 평균 -77 dBFS, 최대 -72 dBFS). Android Studio처럼 옵션 없이 켜면 이 상태이고, `adb emu avd hostmicon`을 실행하면 실제 소리가 들어옵니다 |
| 앱 실행 사용자 | Pleos Connect는 앱을 사용자 10으로 실행합니다. 앱 파일을 꺼내려면 `run-as com.carstage.app --user 10`을 씁니다 |
| 점검용 진단(`--es probe smoke`) | 마이크 1초 녹음과 기어 읽기가 모두 성공: `smoke: PASS` |

- 사람 목소리로 음높이를 잡는지는 아직 확인하지 못했습니다. 사용자가 누른 버튼은 "측정 시작"이었고, 그때는 로그가 남지 않았습니다. 이제는 측정을 멈추면 요약(음높이, 박수 횟수)이 로그에 남습니다.

## 플랫폼 신호 확인 (2026-10-22)

실행 제안과 편의 기능([07](07-convenience-features.md))에 필요한 신호를 Lab의 "플랫폼" 진단(`--es probe platform`)으로 확인했습니다. 다른 앱의 음악은 개발용 시험 재생 앱(`tools/mediatester`)으로 재생했습니다.

| 항목 | 결과 |
|---|---|
| 다른 앱의 음악 재생 감지 | **가능합니다.** 시험 재생 앱이 재생을 시작하자 `AudioManager.registerAudioPlaybackCallback`이 바로 `[MEDIA/MUSIC]`을 알렸고, 멈추자 `none`을 알렸습니다. 권한은 필요 없습니다. `isMusicActive()`는 멈춘 직후에도 잠시 `true`로 남으므로, 재생 목록(`activePlaybackConfigurations`)으로 판단하는 편이 정확합니다 |
| 통화 | `adb emu gsm call`로 전화를 걸자 오디오 모드가 `RINGTONE` → (받으면) `IN_CALL` → (끊으면) `NORMAL`로 바뀌었고, `OnModeChangedListener`가 매번 알렸습니다. 재생 목록에도 `RINGTONE`, `VOICE_COMMUNICATION`이 나타났습니다 |
| 전기차 배터리 | `EvBattery`로 잔량(READ)과 충전 스위치(READ_WRITE)를 읽을 수 있습니다. 권한은 `pleos.car.permission.CAR_ENERGY`입니다. Virtual Console에서 잔량을 42%, 충전을 On으로 바꾸자 앱에서 그대로 읽혔습니다 |
| 기어 | Virtual Console에서 D로 바꾸자 앱이 `GEAR_DRIVE`를, P로 돌리자 `GEAR_PARK`를 읽었습니다 |
| Gleo App Schema | 앱을 설치하자 Gleo(`ai.umos.ambientai`)가 `assets/schema.json`을 읽어 `package=com.carstage.app functionCount=3`으로 등록했습니다. Gleo와 같은 형식의 `OPEN_STAGE` 인텐트를 보내자 앱이 받았고, 앱의 응답 브로드캐스트(`APP_ACTION_RESPONSE`)를 Gleo가 받았습니다 |
| Gleo 음성 실행 | **처음부터 끝까지 동작합니다.** `cmd voiceinteraction show`로 Gleo를 열고 사람이 맥 마이크에 "노래 무대 열어줘"라고 말하자, Gleo가 문장을 그대로 인식했고 기능 목록에서 `com.carstage.app.OPEN_STAGE`를 골라 앱을 실행했습니다. 앱이 "실패(준비 중)"로 답하자 Gleo는 앱이 보낸 문구 대신 자체 문구 "앱 연결에 문제가 있어서 말씀하신 것을 처리하지 못했어요"를 읽었습니다. 그래서 실제 기능에서는 무대를 연 뒤 `success`로 답해야 합니다. 첫 시도에서는 인식 결과가 오지 않았고 두 번째 시도에서 성공했습니다. 맥 음성 합성 소리는 마이크에 너무 작게 들어가서(최대 약 -50 dBFS) 인식되지 않았습니다 |
| 분석 CPU 사용률 | 모든 프레임에 음높이 분석을 돌린 최악의 경우 코어 하나의 1.3~1.4%였습니다. 실제 마이크로 15초 잰 앱 전체 사용률은 코어 하나의 0.7~1.2%(코어 4개 기준 0.2~0.3%)입니다. 정차 중 기준(25% 이하)보다 훨씬 낮습니다. 에뮬레이터는 맥의 CPU를 쓰므로 실차에서는 더 높을 수 있습니다 |
| 기본 앱 Music Visualizer | Pleos 기본 앱 `ai.umos.musicvisualizer`가 상시 서비스로 돌고, 다른 앱이 재생을 시작하면 그 앱을 추적합니다(로그 `Updated playback packages`). 이번 시험에서는 화면에 보이는 변화를 확인하지 못했습니다 |
| 기본 음악 앱 | `com.android.music`은 미디어 플레이어를 만들지 못해 재생하지 못했습니다. 홈 화면의 미디어 위젯도 재생할 소스가 없습니다. 그래서 다른 앱의 음악이 필요한 시험은 시험 재생 앱으로 합니다 |
| Virtual Console | 에뮬레이터 안의 8088 포트에서 도는 웹 서버입니다. 콘솔 클라이언트가 연결되어 있지 않으면 쓰기가 `emulator_inactive`로 거부됩니다. `scripts/vconsole.sh`가 클라이언트 세션을 열고 기어, 충전, 잔량 등을 바꿉니다 |

## 연출 엔진 확인 (2026-10-22)

연출 엔진(`stage/StageEngine`)으로 시험 연출을 실제 차량 출력과 시뮬레이터에 동시에 보내고 되돌리는 것을 Lab의 "연출" 진단(`--es probe stage`)으로 확인했습니다.

| 항목 | 결과 |
|---|---|
| 지원 영역 조회 | 앰비언트(스위치·색·밝기), 좌우 독서등, 풍량, 좌우 시트 통풍을 capability로 찾았습니다. 앰비언트 스위치는 `AUTOMATIC`, `ON`, `OFF` 세 값을 지원합니다 |
| 연출 중 차량 값 | 보통은 앰비언트 스위치(→ 켜짐)와 시트 통풍(운전석 0 → 3, 조수석 0 → 2)만 실제 값에 반영됩니다. 색, 밝기, 독서등은 응답만 오고 값은 그대로입니다. 한 번은 Virtual Console로 앰비언트 값을 쓴 직후 색, 밝기, 조수석 독서등도 반영됐다가 복원됐는데, 원인은 확인하지 못했습니다 |
| 복원 판정 | 진단은 SDK가 돌려준 원래 값끼리 비교합니다. 연출 중 실제로 바뀐 항목이 모두 시작 전 값으로 돌아왔고(`restored`), 차가 바꾸지 않은 항목은 복원을 증명할 수 없으므로 `unverified`로 표시합니다. 시뮬레이터도 시작 상태로 돌아왔고, 전체 판정은 `PASS`입니다 |
| 자동 모드 복원 | 진단이 앰비언트 스위치를 SDK로 `AUTOMATIC`으로 바꾼 뒤 연출(중간에 `ON`)을 하자, 끝난 뒤 `AUTOMATIC`으로 돌아왔습니다(`restored to AUTOMATIC`). 진단은 끝에 스위치를 원래 값(`OFF`)으로 되돌립니다. Virtual Console의 앰비언트 모드(`ambient_mode=Auto`)는 콘솔 화면 값만 바꾸고 SDK가 읽는 값에는 반영되지 않았습니다 |
| 밝기 단계 복원 | 차량은 시작할 때 읽은 원래 값을 그대로 다시 쓰도록 만들었습니다. 에뮬레이터는 밝기 범위가 0~100이고 밝기 쓰기가 값에 반영되지 않아서, 단계가 정확히 돌아오는지는 에뮬레이터로 확인할 수 없습니다 |
| 풍량 | 공조 전원이 꺼져 있으면 쓰기가 `error code 2`(NOT_AVAILABLE)로 거부됩니다. 엔진은 거부된 쓰기를 "바뀌지 않음"으로 보고 복원하지 않으므로, 복원 실패도 생기지 않았습니다(`restore failures=0`). 엔진은 공조 전원을 켜지 않습니다 |
| 연출 도중 취소 | Android Automotive에서는 뒤로 가기를 눌러도 앱 화면이 닫히지 않아 에뮬레이터로 재현하지 못했습니다. 취소된 작업의 `finally`에서 `stop()`을 불러도 복원되는 것은 단위 테스트로 확인했습니다 |
| 앱 강제 종료 | 연출 도중 앱 프로세스가 죽으면 복원되지 않습니다. 다음 실행 때 복원하는 기능을 별도 브랜치로 만듭니다 |

## 앱 뼈대 확인 (2026-10-22)

| 항목 | 결과 |
|---|---|
| 첫 화면 | 앱을 켜면 무대 화면이 나옵니다. "노래 시작"으로 무대가 열리고 "무대 닫기"로 닫히며 차량이 복원됩니다(`stage closed, restored all=true`) |
| Gleo로 무대 열기 | Gleo 형식의 `OPEN_STAGE` 인텐트로 무대가 열렸고 앱이 `SUCCESS`로 답했습니다(`gleo OPEN_STAGE -> SUCCESS`) |
| 백그라운드 | 설정 앱이 전체 화면으로 CarStage를 가리자 앱 전체가 멈춤 상태가 되어 무대가 닫히고 복원됐습니다(`closing stage: app moved to the background`). 홈 키를 눌렀을 때는 Pleos 홈 화면이 CarStage를 한 칸에 계속 보여 줘서 앱이 멈추지 않았고, 무대도 열린 채로 남았습니다 |
| Lab 진단과 무대 | 무대가 열린 상태에서 진단을 시작하면 무대를 먼저 닫고 복원한 뒤 진단합니다(`closed the open stage first`) |

## 안전 규칙 확인 (2026-10-22)

Virtual Console(`scripts/vconsole.sh`)로 차량 상태를 바꾸고 Lab의 "주행 상태" 진단(`--es probe drive`)과 무대 동작을 확인했습니다.

| 상황 | 앱이 읽은 상태 | 결과 |
|---|---|---|
| P, 모두 꺼짐 | `parked=true, gear=GEAR_PARK, restricted=false, defrosting=false, wiping=false` | 무대 허용, 공조 연출 허용 |
| 앞유리 성에 제거 켬 (`/vehicle/hvac front_defroster=true`) | `defrosting=true` | 무대 허용, 공조 연출(풍량) 차단 |
| 앞 와이퍼 Low (`/vehicle/body front_wiper=Low`) | `wiping=true` | 무대 허용, 공조 연출 차단 |
| 기어 D | `parked=false, restricted=true` | 무대 차단. Android 주행 규제(`CarUxRestrictions`)도 D에서 켜졌습니다 |
| D에서 Gleo로 무대 열기 | | 거절하고 Gleo에 `FAIL`("정차 중에만 무대를 열 수 있어요")로 답했습니다 |
| P에서 무대를 연 뒤 D로 바꿈 | | 0.5초 감시가 주행 시작을 잡아 무대를 닫고 복원했습니다(`closing stage: safety: NOT_PARKED`, `restored all=true`). Virtual Console 응답부터 닫힘까지 약 0.3~0.4초였고, 정차 후 화면에 "주행을 시작해서 무대를 멈췄어요. 정차하면 다시 열 수 있어요."가 보였습니다 |
| 앱이 꺼진 상태에서 Gleo로 무대 열기 (P) | | 차량 서비스 연결을 기다린 뒤 기어를 읽어 무대를 열고 `SUCCESS`로 답했습니다. 앱 시작부터 약 1초 걸렸습니다 |
| 다른 경로로 무대가 닫힌 뒤 D로 바꿈 | | 백그라운드 전환으로 무대가 닫히면 감시도 끝나서, 이후 기어를 바꿔도 안전 종료나 안내가 생기지 않았습니다 |
| D에서 CarStage 화면 | | Pleos 플랫폼이 앱 화면 전체를 "주행 중에는 사용할 수 없습니다" 화면으로 가립니다. 주행 중 사용을 선언하지 않은 앱이라서입니다. 화면 버튼도 앱에 닿지 않습니다 |

## 실내 시뮬레이터와 마이크 연출 확인 (2026-10-23)

첫 화면 왼쪽에 실내 시뮬레이터를 그리고, 무대가 열리면 마이크 → 분석(`AudioAnalyzer`) → 연출 규칙(`GlowDirector`) → 차량과 시뮬레이터로 이어지게 했습니다. 맥 출력이 음소거되어 있어서 스피커로 시험음을 틀 수 없었습니다. 그래서 같은 경로에 WAV 파일을 넣는 Lab 재생(`--es replay`)으로 확인했습니다. 시험 파일은 조용한 구간, 두 음, 박수 세 번, 내려가는 음으로 된 14.3초짜리 합성음입니다.

| 항목 | 결과 |
|---|---|
| 첫 화면 | 실내를 위에서 본 그림(앰비언트 띠, 독서등, 시트, 송풍구)과 상태 글자가 보입니다. 무대가 닫혀 있을 때는 시뮬레이터 기본값(흰색 50%)입니다 |
| 녹음 재생 | `replay sing-test.wav off after 14.8 s: 335 frames, 187 sung, 191 loud, 3 claps, 0 silent (longest run 0), loudest -7.4 dBFS, baseline -44.9 dBFS`, `restored all=true`. 큰 소리 구간에서 밝기 99%, 소리가 줄어든 뒤 29%로 내려가고, 노래하는 동안 색이 바뀌었습니다 |
| 박수 | 처음에는 노래가 시작되는 순간까지 박수로 세어 5번이 나왔습니다. 소리가 튄 프레임과 그다음 프레임 모두 음높이가 없을 때만 박수로 세게 바꾸자 3번이 되었습니다. 박수 직후 화면 캡처에서 양쪽 독서등이 켜진 색으로 바뀌었습니다 |
| 실제 마이크 | Gleo로 무대를 열면 `microphone on (device=Car on arm64 emulator (type 15), silenced=false)`, 닫으면 `microphone off after 3.7 s: 87 frames …`. 초당 약 23프레임입니다. 소리가 작아 연출 반응은 재생으로만 확인했습니다 |
| 마이크 무음 | 2초 잡음, 5초 디지털 무음, 3초 잡음으로 된 파일을 재생하자 무음 약 3초 뒤 `only digital silence for about 3 s` 경고와 함께 화면에 "마이크 소리가 들어오지 않아요…" 안내가 나왔고, 소리가 돌아오자 안내가 사라졌습니다. 종료 줄은 `117 silent (longest run 117)`입니다. 무음 프레임은 기준선을 움직이지 않습니다(종료 기준선 -45.1 dBFS) |
| 통화 | 무대가 열린 상태에서 `adb emu gsm call`을 하면 `audio mode RINGTONE: inCall=true` 직후 무대를 닫아 복원하고(`closing stage: call started`), 마이크는 약 40ms 안에 꺼졌습니다. 통화가 끝나면 `audio mode NORMAL`이 기록됩니다. 통화 중에 Gleo로 열면 거절합니다(`stage refused: in a call`). 무대를 여는 확인 중에 전화가 오는 경우는 단위 테스트(`MicGateTest`)로 확인했습니다 |
| 마이크 권한 | 권한을 뺀 상태에서 Gleo로 열면 거절합니다(`stage refused: microphone not allowed`). 화면에서 "노래 시작"을 누르면 권한 창이 뜨고, 거부하면 안내 문구가 나오며, 다시 눌러 허용하면 바로 무대가 열립니다 |
| 주행 시작 | 무대가 열린 채 D로 바꾸면 무대를 닫고 마이크도 꺼집니다 |
| CPU (코어 하나 기준, 4코어) | 무대가 닫혀 있으면 0%입니다. 실제 마이크로 조용할 때는 10~16%, 노래를 재생하는 동안은 19~24%입니다(4코어 전체 기준 5~6%). 스레드별로는 메인 스레드 8~10%, 그리기 4~5%, 분석·연출 1~4%로 화면 갱신이 대부분입니다. 색·밝기 전환 애니메이션을 끄면 15~16%로 내려갑니다. 상태 글자는 영향이 없었습니다 |

- Pleos의 "정차 중 CPU 25% 이하"가 코어 하나 기준인지 전체 기준인지 문서에 나와 있지 않습니다. 코어 하나 기준이면 여유가 적으므로 실차에서 다시 잽니다.

## 강제 종료 후 복원 확인 (2026-10-23)

무대가 차량 설정을 처음 바꾸기 직전에 원래 값을 `files/stage/restore-journal.properties`에 적고(`RestoreJournal`), 무대가 정상적으로 닫히면 지웁니다. 앱이 다시 시작할 때 파일이 남아 있으면 그 값으로 되돌립니다. 차량 값은 Virtual Console 상태(`scripts/vconsole.sh state`)와 Lab 차량 진단으로 확인했습니다.

| 순서 | 결과 |
|---|---|
| 무대 전 | 콘솔 앰비언트 `color #ff00ff, brightness 48`. SDK로 읽으면 스위치 `OFF`, 밝기 48, 색 `0x00FF00FF` |
| 녹음 재생으로 무대를 연 지 5초 | 콘솔 `color #ffb300, brightness 100`. 기록 파일 내용: `AmbientBrightness=48`, `AmbientColor=16711935`, `AmbientSwitch=OFF` |
| `am force-stop`으로 앱 종료 | 무대를 닫지 못해 콘솔이 `color #ffe600, brightness 100`으로 남았습니다 |
| 앱 다시 시작 | 1초 안에 복원 로그(`restoring {AmbientBrightness=48, AmbientColor=16711935, AmbientSwitch=OFF}` → `restored`)가 남았습니다. 콘솔이 `#ff00ff, 48`로 돌아왔고 기록 파일이 지워졌습니다. 화면에 복원 안내가 나왔습니다 |
| 강제 종료 직후 Gleo로 무대 열기 | 앱이 새로 켜지면서 복원을 먼저 끝내고 그다음에 무대를 열었습니다(복원 끝 16:38:39.280, 무대 열림 .327). 무대가 열린 뒤에도 "지난 무대에서 바뀐 차량 설정을 원래대로 되돌렸어요." 안내가 남습니다. 한 번은 앱이 처음 켜질 때의 차량 호출이 느려 복원에 6.2초가 걸렸습니다. 그래서 3초 안에 끝나지 않으면 "되돌리는 중이에요" 안내를 띄우고 최대 20초 더 기다리게 했습니다 |
| 무대가 열린 채 Gleo로 다시 열기 | 복원이 돌지 않고(`whileClosed`) 기록 파일이 그대로 남았습니다. 이어서 강제 종료하자 다음 시작에서 정상적으로 복원했습니다 |
| 정상 종료 | 무대를 닫으면 `restored all=true` 뒤 기록 파일이 지워졌습니다 |

- 앰비언트 색·밝기 쓰기는 Virtual Console 상태에 바로 보입니다. 닫는 도중에는 콘솔 밝기가 잠깐 47로 읽혔다가 2초 뒤 48이 되었습니다.
- 콘솔의 앰비언트 `mode`는 SDK의 앰비언트 스위치 값과 따로 보입니다. SDK가 `OFF`나 `ON`을 읽을 때도 콘솔은 `Auto`였습니다.

## 휴대폰 녹음 재생과 공유 대상 확인 (2026-10-23)

차 안에서 음악에 맞춰 따라 부르며 휴대폰으로 녹음한 팀의 시험 녹음(공개하지 않음, 박수 없음) 두 개(`car-1`, `car-2`)를 녹음 재생으로 넣었습니다. `car-2`는 회귀 시험의 `recordings/regress.m4a`와 같은 녹음입니다. 분석 결과와 판단은 [09](09-voice-reaction-plan.md)에 있습니다.

| 확인 | 결과 |
|---|---|
| 녹음 재생 | `replay car-1.wav off after 84.0 s: 1958 frames, 906 sung, 909 loud, 5 claps, 0 silent (longest run 0), loudest -7.6 dBFS, baseline -35.9 dBFS`, `replay car-2.wav off after 30.9 s: 712 frames, 250 sung, 427 loud, 13 claps, 0 silent (longest run 0), loudest -7.2 dBFS, baseline -43.2 dBFS`. 같은 분석기를 JVM에서 직접 돌린 결과와 숫자까지 같았습니다. 박수 18번은 모두 잘못 센 것입니다 |
| 녹음 올리기 | `adb exec-in run-as … sh -c 'cat > cache/lab/<파일>' < <파일>`이 오류 없이 파일을 잘랐습니다(8,024,064 → 6,782,821바이트, 2,920,448 → 2,122,938바이트). `adb push`로 `/data/local/tmp`에 올린 뒤 `run-as … cp`로 옮기면 md5가 같았습니다. `scripts/replay.sh`는 이 방법으로 올리고 md5를 확인합니다 |
| 재생 중에 다른 재생 요청 | `busy: replay probe is running`으로 거절되었습니다. `off after` 줄이 찍힌 뒤에도 차량 복원이 끝날 때까지는 거절되므로, 앞 재생의 `=== replay end` 줄을 기다린 뒤 보냅니다(`scripts/replay.sh`도 이 줄을 기다립니다) |
| 녹음 재생 9초 뒤 기어 D | 무대가 `closing stage: safety: NOT_PARKED`로 닫히고 `off after 9.0 s`가 남았습니다. 이때 독서등 복원이 좌우 모두 `Exception: java.lang.IllegalArgumentException: 50001`로 실패했습니다(`restored all=false`). 같은 녹음을 기어 P에서 끝까지 재생하고 닫을 때는 독서등 복원이 성공했습니다(`restored all=true`). 주행 상태에서 독서등 쓰기가 거절되는 것으로 보였습니다. 원인과 대응은 아래 "주행 중 복원 확인"에 있습니다 |
| 공유를 받을 수 있는 앱 (`cmd package query-activities --user 10 -a android.intent.action.SEND -t <형식>`) | `image/png`는 인쇄 서비스(`com.android.bips`) 하나뿐이었고, `video/mp4`와 `text/plain`은 없었습니다. 공유 창(`com.android.intentresolver`)은 있습니다 |

## 주행 중 복원 확인 (2026-10-23)

녹음 재생 도중 기어를 D로 바꾸면 독서등 복원이 실패하던 문제의 원인을 확인하고, 정차하면 되돌리도록 바꿨습니다. 차는 주행 중 독서등 쓰기를 여전히 거절하므로, 그동안 독서등은 무대가 남긴 상태로 있습니다. 지금 동작은 이렇습니다. 복원 쓰기는 항상 보내고, 주행 중이라 거절되면 값을 다시 읽습니다. 원래 값이면 복원된 것으로 보고, 아니면 기록에 남겼다가 정차하면 되돌립니다. 무대를 열 때는 기어를 먼저 확인합니다.

| 확인 | 결과 |
|---|---|
| 차량 진단(`--es probe vehicle`)을 기어 D와 P에서 | D에서는 독서등 네 개(1열·2열 좌우)의 켜기가 모두 `Exception: java.lang.IllegalArgumentException: 50001`로 1~4ms 만에 거절되었고, 되돌리기도 같은 오류로 실패했습니다. P에서는 켜기가 모두 `ack=ON`이었습니다. 앰비언트 밝기·색 쓰기는 D에서도 실패하지 않았습니다. 풍량은 D와 P 모두 `error code 2`(공조 전원 꺼짐)였습니다. 같은 진단에서 지원하지 않는 기능의 오류는 `a: ERROR_NOT_SUPPORTED_PROPERTY`처럼 오류 코드 이름으로 찍혔습니다 |
| 오류 코드 | 공식 문서의 Vehicle SDK 오류 목록에 `ERROR_DRIVING_RESTRICTED`(주행 중 제한된 기능)가 있습니다. 50001은 `VehicleException`이 아니라 숫자만 담긴 예외로 와서 목록과 직접 대조할 수는 없지만, P에서는 받아들이고 D에서는 거절하므로 이 제한으로 봅니다 |
| 원래 값이 운전석 켜짐·조수석 꺼짐인 기록으로 기어 D에서 앱 시작 | 앰비언트 밝기는 바로 되돌렸습니다. 운전석만 `restoring [ReadingLight(side=DRIVER)] once the car is parked`로 기록에 남고 정차 대기가 시작되었습니다. 조수석은 대기 목록에도 실패에도 남지 않았습니다. D에서는 독서등 쓰기가 거절되므로, 다시 읽은 값이 원래 값(꺼짐)과 같았던 것으로 봅니다. 이때 화면은 플랫폼이 "주행 중에는 사용할 수 없습니다"로 가려서 앱 안내가 보이지 않습니다 |
| 이어서 기어 D에서 Gleo로 무대 열기 | 복원을 돌리지 않고 기어 확인에서 바로 거절되었습니다(`stage refused: NOT_PARKED`) |
| 이어서 기어 P | 다음 기어 읽기에서 `restored the values the last stage left`가 남고 기록 파일이 지워졌습니다. 화면 안내는 "되돌리지 못했던 차량 설정을 원래대로 되돌렸어요."였습니다 |
| 녹음 재생 9초 뒤 기어 D | 조수석은 `refused while driving, but it already holds its original`이었습니다. 운전석은 앞 시험이 켜 둔 상태(켜짐)를 이번 무대가 원래 값으로 잡았고, 닫을 때 다시 읽은 값은 꺼짐이라 `deferred until parked=1`로 닫혔습니다. P로 바꾸자 되돌려져 기록이 남지 않았습니다. 같은 시험을 한 번 더 하자 두 독서등 모두 다시 읽은 값이 원래 값이라 `restored all=true`로 닫혔습니다. 고치기 전에는 같은 상황에서 `restored all=false`와 50001 실패가 남았습니다 |
| Virtual Console의 독서등 값 | 콘솔이 연결된 상태에서는 앱의 독서등 쓰기가 콘솔 `reading_lights` 값에 반영되었습니다(운전석을 켜짐으로 복원한 뒤 `front_left: true`). 시험이 끝난 뒤 꺼짐으로 되돌렸습니다 |

## 반주 대비 목소리 비율과 환호 확인 (2026-10-23)

소리 분석을 시간 기준선에서 반주 대비 목소리 비율로 바꾸고, 박수 감지를 환호 감지로 바꾼 뒤 녹음 재생(`scripts/replay.sh`)으로 확인했습니다. 설계와 이유는 [09](09-voice-reaction-plan.md)에 있습니다. 환호가 난 시각은 `off after` 줄에 없으므로, 같은 규칙을 옮긴 오프라인 계산(Python)으로 보았습니다.

| 확인 | 결과 |
|---|---|
| car-1 (83.5초, 박수 없음, 환호는 기록하지 않음) | 이전: `906 sung, 909 loud, 5 claps … baseline -35.9 dBFS`. 이후: `906 sung, 958 loud, 0 cheers … background -40.8 dBFS` |
| car-2 (30.4초, 박수 없음, 환호는 기록하지 않음) | 이전: `250 sung, 427 loud, 13 claps … baseline -43.2 dBFS`. 이후: `250 sung, 404 loud, 0 cheers … background -43.4 dBFS`. 최근 목소리 크기를 dB로 평균하던 중간 구현에서는 6.7초 부근의 음높이 없는 큰 소리가 환호 1번으로 잡혔습니다 |
| car-2에 합성 함성 2번(12초, 26초, 1초씩)을 섞은 파일 (에뮬레이터 재생, 파일마다 함성 2번) | 높은 목소리(기본음 196~311 Hz) 함성을 노래와 비슷한 -15 dBFS로 섞으면 `0 cheers`, 노래보다 확실히 큰 -10 dBFS로 섞으면 `2 cheers`, 낮은 목소리(110~147 Hz) 함성을 -10 dBFS로 섞으면 `2 cheers`였습니다. 시험 도중의 규칙(저음 조건, 최근 대비 6 dB)에서는 노래와 비슷한 크기의 함성과 낮은 목소리 함성을 놓쳤고, 그래서 저음 조건을 빼고 최근 대비 기준을 3 dB로 낮췄습니다([09](09-voice-reaction-plan.md) 5장). 최근 크기를 처음 5초 동안 전체 평균으로 내기 전에는 -15 dBFS 함성도 2번 잡혔습니다. 노래와 비슷한 크기라 경계에 있는 경우입니다 |
| car-2를 12초부터 +10 dB로 키운 파일 | `0 cheers` |
| CPU (car-1 재생 중) | 분석 스레드(`wav-replay`)가 20초에 53틱(코어 하나의 약 2.7%), 앱 전체가 10초에 183틱(약 18%)이었습니다. 앱 전체는 이전 측정(19~24%)과 같은 수준이고, 분석 스레드를 뺀 나머지는 이전 측정에서 대부분 화면 갱신이었습니다 |

## 연출 혼합기 확인 (2026-10-23)

연출을 층과 혼합기로 바꾼 뒤 에뮬레이터에서 확인했습니다. 밝기, 색, 독서등 반짝임은 이전과 같게 옮겼습니다. 다른 점은 두 가지입니다. 안전 규칙이 막은 독서등 켜짐을 이제 반짝이는 동안 다시 보내고, 첫 이벤트가 환호이면 색도 함께 보냅니다.

| 확인 | 결과 |
|---|---|
| car-2에 높은 목소리 합성 함성(-10 dBFS)을 섞은 파일 재생 | `2 cheers`, `stage closed, restored all=true`. 함성 중(12.6초) Virtual Console의 앰비언트 밝기는 99%였습니다. 독서등 켜짐은 콘솔 조회에 잡히지 않았습니다. 켜져 있는 시간이 약 0.2초라 조회 사이에 지나간 것으로 봅니다 |
| 콘솔 값을 밝기 45%, 색 `#2962ff`로 두고 car-2 재생 | 재생 중 콘솔은 100%, `#ffff00`이었고, `stage closed, restored all=true` 뒤 2초 시점에는 81%, `#00ff19`였습니다. 몇 초 뒤 다시 읽었을 때는 45%, `#2962ff`였고 SDK도 45를 읽었습니다. 2초 시점의 SDK 값은 읽지 않아, 늦은 것이 콘솔 표시인지 차량 값인지는 확인하지 못했습니다 |
| 연출 진단(`--es probe stage`) | `stage restore: PASS (restore failures=0, deferred=0, simulator back=true)`. 고정된 시험 연출을 엔진으로 바로 보내므로 혼합기를 거치지 않으며, 엔진 복원만 확인합니다 |
| `scripts/check.sh --device` | 통과(마이크 신호, 기어 읽기) |

## 곡 분위기 확인 (2026-10-23)

곡 분위기 층(드럼 세기와 박자의 규칙성으로 "차분 · 보통 · 신남"을 정하고, 바탕 밝기와 노래할 때의 색 범위를 바꿈)을 넣은 뒤 녹음 재생(`scripts/replay.sh`)으로 확인했습니다. 설계와 오프라인 시험은 [09](09-voice-reaction-plan.md) 5장 "곡 분위기 층"에 있습니다. 음악 4곡은 Wikimedia Commons의 CC BY 3.0, CC0 음원을 30초부터 60초만 잘라 썼고, 레포에는 넣지 않았습니다. 리뷰를 반영한 최종 빌드로 car-1, "Big Rock", 두 피아노 곡을 다시 재생했고, 숫자가 첫 빌드와 같았습니다(녹턴의 `last beat`만 201에서 74 BPM으로 바뀜). car-2와 "Laser Groove"는 첫 빌드의 결과입니다.

| 재생 | `off after` 줄의 분위기 | 그 밖 |
|---|---|---|
| car-1 (차 안 녹음, 84초) | `0 calm / 39 moderate / 0 lively (0 changes)` | 노래 프레임, 큰 소리 프레임, 환호(`906 sung, 958 loud, 0 cheers`)가 이전 재생과 같았습니다 |
| car-2 (차 안 녹음, 31초) | `0 calm / 13 moderate / 0 lively (0 changes)` | `250 sung, 404 loud, 0 cheers`로 이전과 같았습니다 |
| "Big Rock" (록) | `0 calm / 0 moderate / 27 lively (0 changes, last beat 108 BPM)` | |
| "Laser Groove" (전자음악) | `27 lively (0 changes, last beat 141 BPM)` | |
| 짐노페디 1번 (피아노 독주) | `27 calm (0 changes)` | 최종 빌드로 재생하는 중 6초 간격으로 본 시뮬레이터의 앰비언트 색은 `#FFC640`, `#FF6640`, `#FF7940`(색상 약 42°, 10°, 15°, 채도 75%)으로, 차분한 곡의 범위(0~50°) 안에서 움직였습니다. **환호가 2번 잡혔습니다**(`2 cheers`). 피아노 화음이 반주보다 크게 튀어 음높이 없이 읽힌 것으로 봅니다. 밝기도 92%까지 올랐습니다. 피아노 선율을 목소리로 읽는 참여 층의 기존 한계입니다 |
| 쇼팽 녹턴 Op. 15 No. 1 (피아노 독주) | `27 calm (0 changes, last beat 74 BPM)` | 환호 1번(`1 cheers`). 첫 빌드는 박자를 201 BPM까지 찾고 있었습니다(`last beat 201 BPM`). 문서의 60~180 BPM에 맞게 고쳤고, 판정은 바뀌지 않았습니다 |

- 모든 재생이 `stage closed, restored all=true`로 끝났습니다.
- CPU("Big Rock" 재생 중 20초): 분석 스레드(`wav-replay`)가 56틱(코어 하나의 약 2.8%), 앱 전체가 345틱(약 17%)이었습니다. 곡 분위기 판정을 넣기 전(분석 스레드 약 2.7%, 앱 전체 약 18%)과 같은 수준입니다.
- 피아노 독주에서 잡힌 환호는 이번 변경과 관계없는 호응 규칙의 한계입니다. 뒤에 평탄도 조건으로 크게 줄였습니다(녹턴 전곡 110초의 1번은 남음, 아래 "피아노 화음 오검출 확인").

## 곡 종료 감지 확인 (2026-10-23)

곡 종료 감지(`SongEndDetector`)를 넣은 뒤 녹음 재생(`scripts/replay.sh`)으로 확인했습니다. 기준과 오프라인 비교는 [09](09-voice-reaction-plan.md) 5장 "곡 종료 감지"에 있습니다. 곡이 끝나면 `song ended` 줄이 남고, `off after` 줄 끝에 끝난 곡 수가 붙습니다.

| 재생 | 결과 |
|---|---|
| 록 60초 → 1.5초 틈(분홍 잡음, 소리보다 30 dB 아래) → 피아노 30초 | `song ended` 1번, `1 songs ended`. 분위기도 틈 뒤에 신남에서 차분으로 바뀌었습니다(`10 calm / 2 moderate / 31 lively`) |
| car-1 (차 안 녹음, 84초) | `0 songs ended` |
| 짐노페디 1번 전곡(187초, 끝에 조용한 차 소리 3초) | 끝에서 `song ended` 1번, `1 songs ended`. 같은 재생에서 환호가 8번 잡혔습니다(`8 cheers`). 피아노 화음을 환호로 읽는 기존 한계입니다([09](09-voice-reaction-plan.md) 5장) |

- 모든 재생이 `stage closed, restored all=true`로 끝났습니다.
- CPU(짐노페디 전곡 재생 중 20초): 분석 스레드(`wav-replay`)가 56틱(코어 하나의 약 2.8%), 앱 전체가 338틱(약 17%)이었습니다. 곡 종료 감지를 넣기 전과 같은 수준입니다.

## 흥 점수와 피날레 확인 (2026-10-23)

흥 점수, 결과 카드, 피날레, "곡 끝내기"(화면 버튼과 Gleo `FINISH_SONG`)를 넣은 뒤 녹음 재생으로 확인했습니다. 화면 버튼은 아래 "최종 빌드" 줄에서 따로 눌렀습니다. 이때 Gleo `FINISH_SONG`의 성공 응답은 "곡을 마쳤어요. 흥 점수를 확인해 보세요."였고, 리뷰 뒤 점수를 약속하지 않는 "곡을 마쳤어요."로 바꿨습니다. 점수 방식은 [09](09-voice-reaction-plan.md) 5장 "흥 점수"에 있습니다.

| 확인 | 결과 |
|---|---|
| 록 60초 → 1.5초 틈 → 피아노 30초 재생, 틈에서 곡 종료 | `song ended` 뒤 바로 `song scored 6 (BASIC) over 61 s: participation 5, heat 0, response 0, completion 1`. 노래하지 않은 연주곡이라 낮게 나왔습니다. 1초 뒤 시뮬레이터는 밝기 100%, `#FFF1E0`(기본 등급의 따뜻한 흰색)이었고, 결과 카드("6점", "좋은 출발이에요")가 무대가 열린 채로 보였습니다 |
| 같은 재생에서 피아노 18초째에 `adb shell am start -a FINISH_SONG -p com.carstage.app --es ACTION_PARAM '{}'` | `gleo FINISH_SONG -> SUCCESS`, `song ended by request`, `song scored 74 (GOOD) over 18 s: participation 50, heat 17, response 0, completion 7`. 피아노 선율을 목소리로 읽어 참여가 만점이었습니다(알려진 한계). 결과 카드에 "74점", "흥이 올랐어요"가 보였습니다 |
| 최종 빌드: 같은 파일의 록 56초째에 화면의 "곡 끝내기" 버튼을 누름(`adb shell input tap`, 위치는 `uiautomator dump`로 찾음) | `song ended by request`, `song scored 7 (BASIC) over 56 s: participation 6, heat 0, response 0, completion 1`. 5초 뒤 록이 끝나는 틈은 `song end heard right after one by hand, ignored`로 무시되어 결과가 한 번만 나왔습니다 |
| 무대가 닫힌 뒤 `FINISH_SONG` | `gleo FINISH_SONG -> FAIL`, Gleo에 "열려 있는 무대가 없어요. 먼저 노래 무대를 열어 주세요."를 보냈습니다. Gleo가 대기 상태라 응답은 버려졌습니다(`Reject AppActionResponse … status: Sleeping`) |

- 재생은 `stage closed, restored all=true`로 끝났습니다. 결과 카드는 무대를 닫은 뒤에도 "확인"을 누를 때까지 남았습니다.
- `off after` 줄의 끝난 곡 수(`1 songs ended`)는 소리로 들은 끝만 셉니다. 손으로 끝낸 곡은 `song ended by request` 줄로 남습니다.

## 피아노 화음 오검출 확인 (2026-10-23)

환호 판정에 목소리 대역의 스펙트럼 평탄도 조건(0.01 미만이면 화음처럼 몰린 프레임으로 셈)을 더한 뒤 녹음 재생으로 확인했습니다. 근거와 오프라인 비교는 [09](09-voice-reaction-plan.md) 5장의 호응 규칙 변경 목록("화음을 거르는 평탄도 조건")과 그 아래 표에 있습니다.

| 재생 | 바꾸기 전 | 바꾼 뒤 |
|---|---|---|
| 짐노페디 1번 전곡(187초) | `8 cheers` | `0 cheers` |
| 쇼팽 녹턴 60초(30~90초, 오프라인 계산에서 남은 110초의 오검출은 들어 있지 않음) | `1 cheers` | `0 cheers` |
| car-2에 몇 명의 "hurray"를 -10 dBFS로 2번 섞은 파일 | 2번(오프라인) | `2 cheers` |
| car-2에 공연장 박수를 -15 dBFS로 2번 섞은 파일 | 2번(오프라인) | `2 cheers` |
| car-1 | `0 cheers` | `0 cheers` |

- 모든 재생이 `stage closed, restored all=true`로 끝났습니다.
- CPU(짐노페디 재생 중 20초): 분석 스레드(`wav-replay`)가 59틱(코어 하나의 약 3.0%)이었습니다. 평탄도는 반주보다 12 dB 이상 큰 프레임에서만 계산합니다. 앱 전체는 422틱(약 21%)으로, 같은 곡을 재생한 곡 종료 감지 확인 때(약 17%)보다 높게 나왔습니다. 이전 스레드별 측정(곡 분위기 확인)에서는 앱 CPU의 대부분이 화면 갱신(RenderThread와 메인 스레드)이었습니다. 이번에는 스레드별로 재지 않았고, 정차 중 25% 기준에 가까워서 실차에서 다시 잽니다.

## 실행 제안과 이어서 부르기 확인 (2026-10-23)

첫 화면의 실행 제안([07](07-convenience-features.md) 1장)과, 통화·주행으로 멈춘 무대를 이어서 부르자는 제안을 넣은 뒤 리뷰를 반영한 최종 빌드로 확인했습니다. 다른 앱의 음악은 시험 재생 앱(`tools/mediatester`)으로 틀었습니다. 시간은 로그의 시각으로 쟀습니다.

| 확인 | 결과 |
|---|---|
| 음악 없이 앱을 엶(첫 빌드) | "음악 앱에서 노래를 틀어 주세요", 버튼 "노래 시작" |
| 시험 재생 앱이 재생 중일 때 앱을 엶 | 로그 `music playing=true at start`, 화면 "노래가 나오고 있어요. 바로 무대를 열까요?", 버튼 "무대 열기". 첫 빌드에서 재생을 나중에 시작했을 때는 `music playing=true`가 3초 안에 남았습니다 |
| 무대가 열린 채 `adb emu gsm call` | 시험 재생 앱이 오디오 포커스를 잃어 `music playing=false`(통화 모드 0.5초 전), 이어서 `audio mode RINGTONE: inCall=true` 1 ms 뒤 `closing stage: call started`, `restored all=true`. 화면 "통화가 끝나면 이어서 부를 수 있어요", 안내 "전화가 와서 무대를 멈췄어요." |
| 받은 뒤 끊음 | `audio mode NORMAL: inCall=false` 9 ms 뒤 `offering to sing on after the call`. 화면 "통화가 끝났어요. 이어서 부를까요?", "이어서 부르기", "괜찮아요". 멈춤 안내는 지워졌습니다 |
| 무대가 열린 채 기어 D(`scripts/vconsole.sh gear D`) | `closing stage: safety: NOT_PARKED`, `restored all=true`. Pleos가 앱 화면을 "주행 중에는 사용할 수 없습니다" 화면으로 가렸습니다 |
| 다시 기어 P | `offering to sing on after the drive`가 기어 D에서 무대가 닫힌 뒤 4.0초에 남았습니다(P로 바꾼 직후, 기어는 2초마다 읽음). 화면 "다시 정차했어요. 이어서 부를까요?" |

## 충전 연동 확인 (2026-10-24)

`scripts/vconsole.sh charging on`과 `soc 64`로 충전 중으로 두고 앱을 열었습니다.

| 시점 | 로그와 화면 |
|---|---|
| 앱을 엶 | 로그 `ev battery: state of charge supported, charge switch supported`. 화면 제목 아래 "충전 중 · 64%" |
| Gleo `OPEN_STAGE`로 무대를 열고 5초 뒤 `charging off` | `charging off` 뒤 6초 안에 로그 `charging stopped at 64%`, `charging stopped: asking whether this song is the last`(배터리는 5초마다 읽음). 화면 "충전이 끝났어요. 마지막 곡으로 마무리할까요?", "마지막 곡으로", "계속 부르기" |
| "마지막 곡으로" | 로그 `the song being sung is the last`. 화면 "이번 곡이 끝나면 무대를 닫을게요" |
| "곡 끝내기" | 로그 `song ended by request`(09:08:17.114)와 `song scored 0 (BASIC) over 23 s`(방의 소리만 들음), 3.5초 뒤 `the last song after charging is over`(20.616), `stage closed, restored all=true`(20.695). 화면에 그 곡의 결과 카드 "0점"과 안내 "충전이 끝나서 마지막 곡으로 무대를 마쳤어요." |

- 위 표의 시각과 로그는 리뷰를 반영한 최종 빌드로 다시 돌린 것입니다(잔량 64%로 시험). 처음 빌드에서도 같은 순서로 3.5초 뒤에 닫혔습니다.

## 효과음 확인 (2026-10-24)

CC0 효과음 세 개(박수, 드럼롤, 팡파레, README "효과음 출처")를 `SoundPool`로 재생합니다. 용도는 `USAGE_ASSISTANCE_SONIFICATION`이고, 재생할 때마다 오디오 포커스를 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`으로 요청했다가 끝나면 반납합니다.

| 확인 | 결과 |
|---|---|
| 2인 대결 녹음 재생 | 로그 `sound APPLAUSE playing for 4100 ms`가 운전석 차례가 끝나고 0.01초 뒤, `sound DRUMROLL playing for 4000 ms`가 발표 시작 0.99초 뒤(좌우가 번갈아 켜지기 시작), `sound FANFARE playing for 1740 ms`가 5.94초 뒤(승자 조명)에 남았습니다. 길이는 파일에서 읽은 값입니다 |
| 포커스 | 박수가 나오는 동안 `dumpsys audio`의 포커스 목록에 `com.carstage.app gain: GAIN_TRANSIENT_MAY_DUCK`(용도 `USAGE_ASSISTANCE_SONIFICATION`)이 올라갔습니다 |
| 다른 앱의 음악과 함께 | 시험 재생 앱(`USAGE_MEDIA`, 포커스 `GAIN`)이 트는 동안 Gleo로 무대를 열고 `FINISH_SONG`으로 곡을 끝냈습니다. 박수가 나오는 동안 시험 재생 앱의 포커스는 `loss: none`이고 멈춤 로그가 없었습니다. 효과음 용도의 포커스는 음악 앱을 멈추지 않고 함께 재생됩니다. 음악 소리를 줄이는 덕킹은 에뮬레이터에서 일어나지 않았고, 실차의 오디오 정책을 따릅니다 |
| 소리가 끝난 뒤 | 박수가 끝나고 5초 뒤 `dumpsys audio`의 포커스 목록에 앱이 없었습니다(반납됨) |
| 박수 중에 전화 | 박수 1.1초째에 `adb emu gsm call`을 보내자 `sound effect focus lost (-2)`로 소리가 멈추고, 이어서 `closing stage: call started`, `restored all=true`가 남았습니다 |

- 위 두 줄은 리뷰를 반영한 빌드로 확인했습니다. 이 빌드는 파일 길이에 0.2초를 더해 포커스를 잡습니다(로그 `playing for 4300 ms`).
- 효과음이 나오는 동안 환호로 판정된 소리가 없어서, 그 판정을 버리는 동작(로그 `cheer ignored: the stage's own sound was playing`)은 아직 확인하지 못했습니다. 에뮬레이터 안에서는 재생음이 마이크로 들어오지 않지만(위 "오디오"), 맥 스피커에서 맥 마이크로는 들어올 수 있습니다. 위 시험에서도 시험 재생 앱의 신호음이 그렇게 들어와 그 곡이 "참여 50/50"을 받았습니다.

## 등급별 효과음 확인 (2026-10-24)

피날레 효과음을 등급마다 다르게 했습니다(기본 짧은 박수, 좋음 박수, 최고 팡파레와 박수). 새 Lab 진단 `--es probe sounds`로 효과음 다섯 개를 5초 간격으로 한 번씩 재생했습니다.

| 효과음 | 로그(포커스를 잡은 시간, 파일 길이 + 0.2초) |
|---|---|
| 짧은 박수 | `sound SHORT_APPLAUSE playing for 1800 ms` |
| 박수 | `sound APPLAUSE playing for 4300 ms` |
| 팡파레와 박수 | `sound OVATION playing for 4300 ms`(둘 중 긴 박수의 길이). 두 소리 중 하나라도 재생에 실패하면 남는 `failed to play` 로그가 없었습니다 |
| 드럼롤 | `sound DRUMROLL playing for 4180 ms` |
| 팡파레 | `sound FANFARE playing for 1940 ms` |

- `dumpsys audio`의 재생 목록에는 `SoundPool` 재생이 "재생 중"으로 나오지 않습니다. 두 소리가 모두 시작됐는지는 위 로그로 확인했고, 함께 들리는지는 이 진단을 돌려 귀로 확인해야 합니다.

## 차 종류 확인 (2026-10-24)

차가 전기차인지, 엔진이 있는지는 Pleos SDK가 아니라 Android 표준 차량 정보(`CarPropertyManager`)의 연료 종류 `INFO_FUEL_TYPE`(0x11410105)로 읽습니다.

- 권한 `android.car.permission.CAR_INFO`는 `protectionLevel:normal`입니다(`pm list permissions -f`). 앱을 `-g` 없이 설치해도 `granted=true`라서 사용자에게 묻지 않습니다.
- 에뮬레이터는 연료 종류 `[10]`(`FuelType.ELECTRIC`)을 돌려줍니다. 앱을 시작하자 로그 `car kind ELECTRIC (fuel types [10])`가 남았습니다.
- `dumpsys car_service --hal`에는 `INFO_MAKE`, `INFO_MODEL`, `INFO_MODEL_YEAR`, `INFO_EV_BATTERY_CAPACITY`, `INFO_EV_CONNECTOR_TYPE`도 있습니다(읽기 전용, 값이 바뀌지 않는 속성).
- 연료만 쓰는 차와 플러그인 하이브리드의 동작은 에뮬레이터의 연료 종류를 바꿀 수 없어서 단위 테스트(`CarKindTest`)로만 확인했습니다.

## 배터리 보호 확인 (2026-10-24)

`scripts/vconsole.sh soc 15`, `charging off`로 두고(충전 중이면 배터리 규칙이 막지 않음) 2인 대결 녹음(아래 "2인 대결 확인"과 같은 파일)을 재생하며 `START_BATTLE`을 보냈습니다.

| 시점 | 로그와 화면 |
|---|---|
| 무대가 열림 | 로그 `battery low at 15%: holding back climate and seat effects`. 화면 안내 "배터리가 20%보다 적어서 공조와 시트 연출은 쉬고 조명으로만 반응할게요." |
| 발표 7초째(승자 조수석) | 시뮬레이터 앰비언트 빨강(`#FF3030`) 100%, 조수석 독서등 켬, 시트 통풍은 운전석·조수석 모두 0단. 잔량이 충분할 때는 여기서 조수석 통풍이 3단이었습니다(아래 "2인 대결 확인") |
| 파일 끝 | 로그 `battery rules held back 96 commands in this stage`, `the command rules held back 96 commands in this stage`, `stage closed, restored all=true`. 막힌 96개는 모두 배터리 규칙이 막은 것으로, 발표의 승자 부분 4초 동안 이벤트마다 다시 보냈다가 막힌 조수석 통풍 켜기로 보입니다(승자 부분 94프레임과 그동안의 곡 분위기 이벤트 2번) |

- 표의 로그는 리뷰를 반영한 최종 빌드로 다시 돌린 것입니다.
- 잔량 15%로 Gleo `OPEN_STAGE`를 보내 무대를 연 뒤 같은 명령을 한 번 더 보내도(이미 열린 무대) 배터리 안내가 남았습니다. 이어서 `charging on`을 보내자 로그 `battery charging at 15%: climate and seat effects again`이 남고, 화면의 배터리 안내가 내려가고 "충전 중 · 15%"가 나왔습니다(화면 캡처로 확인. `uiautomator dump`는 연출 애니메이션 중에 실패하고 지난 파일을 남기므로 이 확인에 쓰지 않습니다).
- 에뮬레이터에는 전기차 배터리가 있어서 공회전 경고(전기차 배터리가 없는 차, 무대마다 30분에 한 번)와 충전 중 예외는 단위 테스트(`BatterySaverTest`)로만 확인했습니다.

## 착석 감지 확인 (2026-10-24)

Virtual Console의 좌석 착석은 `scripts/vconsole.sh set /vehicle/seat kind=occupied area=<driver|passenger|rear_left|rear_right> value=<true|false>`로 바꿉니다(콘솔 카탈로그의 "Seat Occupancy"). `value=true`가 Occupied, `false`가 Empty이고, 콘솔에는 2열 가운데가 없습니다. 바꾼 뒤 Lab의 차량 진단(`--es probe vehicle`)으로 SDK의 `getCurrentSeatOccupancy` 값을 읽었습니다.

| 콘솔 값 | SDK 값 |
|---|---|
| 콘솔을 운전석 Occupied, 조수석 Occupied, 뒷좌석 Empty로 맞춤 | `ROW_1_LEFT=false`, `ROW_1_RIGHT=false`, `ROW_2_*=true`(콘솔에 없는 2열 가운데 포함) |
| 운전석 Empty로 바꿈 | `ROW_1_LEFT=true` |
| 뒷좌석 왼쪽 Occupied로 바꿈 | `ROW_2_LEFT=false` |

- SDK 값은 콘솔에 있는 네 자리에서 모두 콘솔과 반대였습니다. 콘솔이 차량 HAL에 쓰는 표준 속성 `SEAT_OCCUPANCY`(0x15400BB0)는 콘솔 값과 맞습니다. 뒷좌석 왼쪽을 Occupied로 바꾸자 `dumpsys car_service --hal`의 마지막 이벤트(`lastEvent`)가 `zone: 0x10`(`ROW_2_LEFT`), `int32Values: [2]`(Android 표준 `OCCUPIED`)였고, 나중에 조수석을 Empty로 둔 뒤에는 `zone: 0x4`(`ROW_1_RIGHT`), `int32Values: [1]`(`VACANT`)이었습니다. 따라서 SDK가 이 표준 속성을 읽는다면, Boolean으로 바꾸는 과정에서 뒤집히는 것으로 보입니다. 공식 문서는 반환값을 "착석 상태"라고만 설명합니다.
- 앱은 함수 이름대로 true를 "앉음"으로 읽습니다(`vehicle/SeatOccupancyReader`). 그래서 에뮬레이터에서 제안을 보려면 콘솔의 앞좌석을 Empty로 둡니다. 실차가 어느 쪽 값을 주는지는 실차에서 확인합니다.
- `cmd car_service get-property-value`는 user 빌드라서 거절됩니다(`requires non-user build`).
- 대결 제안: 콘솔의 앞좌석 둘을 Empty로 두고 Gleo `OPEN_STAGE`로 무대를 열자 12 ms 뒤 `offering a duel: both front seats are taken`이 남고, 문구 "두 분이 타셨네요. 노래 대결도 해 보세요"와 채워진 "대결 시작" 버튼이 나왔습니다. 조수석을 Occupied로 바꾸면 `no longer offering a duel: a front seat is free`가, 다시 Empty로 바꾸면 `offering a duel …`이 남았습니다. 콘솔 값을 바꾸기 직전에 남긴 표시 로그부터 이 줄까지 0.5~2.9초 걸렸습니다(6번, 3초마다 읽음).

## 2인 대결 확인 (2026-10-23, 리뷰 수정 뒤 2026-10-24 다시 확인)

2인 대결(턴제, 차례 색, 발표 연출, 결과 화면)을 넣은 뒤 녹음 재생으로 확인했습니다. 시험 파일은 공개 라이선스 곡 두 개("Big Rock" 40초, "Laser Groove" 40초)를 1.5초 틈으로 잇고, 끝에 조용한 차 소리 16초를 붙인 것입니다(레포에 넣지 않음). 재생을 시작하고 1초 뒤 `adb shell am start -a START_BATTLE -p com.carstage.app --es ACTION_PARAM '{}'`로 대결을 시작했습니다. 다른 앱의 음악은 틀지 않았으므로 대결은 기다리지 않고 바로 시작합니다. 차량 값은 앱의 실내 시뮬레이터 화면에서 읽었습니다.

| 시점 | 로그와 화면 |
|---|---|
| 시작 | 로그 `duel requested`, `gleo START_BATTLE -> SUCCESS`, `duel turn: turn DRIVER`. 시뮬레이터 앰비언트 파랑(`#2060FF`), 화면 "운전석 차례예요" |
| 40초, 첫 곡이 끝남 | 로그 `song ended`, `duel turn: turn PASSENGER, scores {DRIVER=6}`. 피날레 뒤 빨강(`#FF3030`), 화면 "조수석 차례예요", "운전석 6점" |
| 81초, 둘째 곡이 끝남 | 로그 `duel announcing: … scores {DRIVER=6, PASSENGER=53}, winner PASSENGER`. 발표 중 파랑과 운전석 독서등, 빨강과 조수석 독서등이 번갈아 켜졌습니다 |
| 발표 7초째 | 빨강 100%, 조수석 독서등 켬, 조수석 시트 통풍 3단. 운전석 시트 통풍은 0단 그대로입니다(진 쪽은 건드리지 않음, 10-24) |
| 발표 10초 | 로그 `duel done`. 화면 "승자는 조수석!", "운전석 6점 · 조수석 53점", "다시 대결", "확인". 두 독서등과 조수석 시트 통풍이 꺼졌고, 조명은 노래 층으로 돌아갔습니다(곡 분위기가 차분으로 바뀌어 빨강~호박색 범위) |
| 파일 끝 | 로그 `stage closed, restored all=true`. 무대가 닫힌 뒤에도 결과 카드는 남습니다 |

- 두 곡 모두 노래하지 않은 연주곡입니다. 조수석 점수가 높은 것은 "Laser Groove"의 신시사이저 선율을 참여로 읽었기 때문입니다(녹음 속 선율을 참여로 읽는 알려진 한계, [09](09-voice-reaction-plan.md) "흥 점수").
- 무대가 닫힌 채 Gleo `START_BATTLE`(10-24): 로그가 `stage open` → `duel requested` → `microphone on` → `duel turn: turn DRIVER` → `gleo START_BATTLE -> SUCCESS` 순서로 남았습니다. Gleo는 오디오 스레드가 대결을 실제로 시작한 뒤에 답하므로, 새로 연 무대에서는 "운전석부터 한 곡 불러 주세요"라고 답합니다. 화면은 "운전석 차례예요"와 10초 조건 안내였습니다.
- 운전석 차례 중에 "무대 닫기"(10-24): `stage closed, restored all=true`. 대결 카드가 사라지고 "음악 앱에서 노래를 틀어 주세요"로 돌아왔습니다.
- 곡 중간에 대결을 시작해 지금 곡이 끝나기를 기다리는 경로는 다른 앱의 재생이 있어야 해서 에뮬레이터에서는 확인하지 않았습니다(시험 재생 앱이 실제 소리를 냄). 단위 테스트(`GlowDirectorTest`)로만 확인했습니다.

## 출시 빌드 확인 (2026-10-24)

Lab을 개발 빌드 소스 세트(`app/src/debug`)로 옮긴 뒤, 출시 빌드(`assembleRelease`)를 SDK 디버그 키로 서명해서 에뮬레이터에 설치했습니다. 출시 서명 설정은 아직 없으므로, 이 서명은 로컬 확인용입니다.

| 확인 | 결과 |
|---|---|
| APK의 Lab 클래스(`dexdump`로 `com/carstage/app/lab/` 클래스 수) | 개발 빌드 206개, 출시 빌드 0개 |
| APK 크기 | 개발 빌드 30MB, 서명 전 출시 빌드 22MB(차이에는 개발 빌드에만 들어가는 Compose 도구도 포함됩니다) |
| 설치한 출시 빌드 | `dumpsys package`에 `DEBUGGABLE` 표시가 없습니다. 개발 빌드와 서명이 같아 `adb install -r`로 덮어 설치되고 앱 데이터가 유지됩니다 |
| 첫 화면 | 오른쪽 위 "개발자 Lab" 버튼이 없습니다. 나머지는 개발 빌드와 같습니다 |
| `--es probe drive`, `--es replay mic-sample.wav` | 로그 `probe "drive" ignored: the Lab is only in debug builds`, `replay "mic-sample.wav" ignored: …`. 화면은 첫 화면 그대로입니다 |
| Gleo `OPEN_STAGE` → "무대 닫기" | `stage open, driving [vehicle, simulator]`, `gleo OPEN_STAGE -> SUCCESS`, `microphone on` → `stage closed, restored all=true` |

- 개발 빌드에서는 같은 진단 명령이 이전과 같이 동작했습니다(`=== drive start` … `=== drive end`, Lab 화면에 결과 표시).
- 개발 빌드를 설치한 직후 `--es probe drive`로 앱을 시작하자, 진단은 돌았지만 앱 작업이 내비게이션 뒤에 놓였습니다(`dumpsys activity`의 작업 `visible=false`). 앱 아이콘을 눌러도 앞으로 나오지 않았고, `am start -n com.carstage.app/.MainActivity`를 다시 보내자 보였습니다. 바로 전에 홈 키를 누른 상태였기 때문인지, 같은 순서로 다시 확인하지는 않았습니다.

## 회귀 시험 확인 (2026-10-24)

에뮬레이터 확인을 한 번에 돌리는 `scripts/regress.sh`를 만들고 돌려 봤습니다. 시나리오마다 기어 P, 효과음 끔, 무대 닫힘, 빈 로그에서 시작하고, 기다리는 로그 줄이 정해진 시간 안에 나오는지로 판정합니다. 어느 줄부터 볼지는 기기 시각(`adb shell date +%s.%N`)으로 표시하고 `adb logcat -t <시각>`으로 읽습니다. 시각을 밀리초까지 주면 그 앞의 줄은 나오지 않았습니다.

| 확인 | 결과 |
|---|---|
| `scripts/regress.sh --install` (통화 제외 다섯 시나리오) | `gleo` 3초, `drive` 3~4초, `recovery` 4초, `probes` 3초, `replay` 31~32초로 모두 PASS였고, 설치를 포함해 48초 걸렸습니다(두 번 돌림). `replay`의 결과 줄은 `car-2.wav off after 30.9 s: 712 frames, 250 sung, 404 loud, 0 cheers … 0 songs ended`로, 10-23 기록과 같았습니다 |
| 효과음을 끈 채 Lab 효과음 진단(`--es effects off --es probe sounds`) | `test: effects muted` 뒤 다섯 효과음이 모두 `sound <효과음> muted for <ms> ms`로만 남았고(짧은 박수 1800, 박수 4300, 팡파레와 박수 4300, 드럼롤 4180, 팡파레 1940), 소리는 나지 않았습니다. 길이는 소리를 낼 때 오디오 포커스를 잡는 길이와 같습니다 |
| 실패 판정 | 마이크 권한을 회수하고(`pm revoke --user 10`) `gleo`만 돌리자 `no line matching 'stage open, driving' within 15 s`와 앱 로그(`stage refused: microphone not allowed`, `gleo OPEN_STAGE -> FAIL`)를 보여 주고 종료 코드 1로 끝났습니다. 권한을 다시 준 뒤에는 PASS, 종료 코드 0이었습니다 |
| 끝난 뒤 상태 | 마지막 로그가 `closing stage: closed by a test`, `test: stage closed (it was not open)`, `test: effects on` 순서였습니다. 무대가 닫힌 것을 확인한 뒤에 효과음을 켭니다 |
| 도중에 SIGINT(Ctrl-C) | `drive`가 끝나고 `gleo`가 시작된 때에 보내자 종료 코드 130으로 멈췄고, 정리가 끝까지 돌았습니다(무대 닫힘, 효과음 켬, Virtual Console의 기어 `Park`). 비대화형 셸에서 `&`로 띄우면 SIGINT가 무시된 채 시작해서 끝까지 돌았으므로, 시험은 perl로 SIGINT를 기본 동작으로 되돌린 뒤 실행했습니다. 터미널에서 Ctrl-C를 누르는 경우와 같습니다 |

- 통화 시나리오(`scripts/regress.sh call`)는 에뮬레이터 전화벨이 맥 스피커로 울릴 수 있어 따로 돌렸습니다. 5초 만에 PASS였고, 로그는 `audio mode RINGTONE: inCall=true` → `closing stage: call started` → `stage closed, restored all=true` → `audio mode IN_CALL`(받음) → `audio mode NORMAL: inCall=false`(끊음) → `offering to sing on after the call` 순서였습니다. 끝난 뒤 `adb emu gsm list`에 남은 통화는 없었습니다. 기다리는 줄은 10-23 "실행 제안과 이어서 부르기 확인"의 통화 시험 로그에서 가져왔고, 시나리오도 그때처럼 전화를 받은 뒤 끊습니다.

## 차량 제어 권한이 없을 때 (2026-10-24)

Vehicle Control이 Pleos 문서에 "Coming Soon"이라 실차에서 제어가 막힐 수 있으므로, 매니페스트에서 제어 권한 세 개(`CONTROL_CAR_LIGHTS`, `CONTROL_CAR_CLIMATE`, `CONTROL_CAR_SEATS`)를 잠시 뺀 개발 빌드로 확인했습니다. 읽기 권한(`CAR_LIGHTS`, `CAR_CLIMATE`, `CAR_SEATS`)은 그대로 두었습니다.

| 확인 | 결과 |
|---|---|
| 설치한 앱의 권한(`dumpsys package`) | `CONTROL_CAR_*`가 없습니다 |
| 차량 진단(`--es probe vehicle`) | capability 조회는 그대로 답했고, 접근은 여전히 `access=READ_WRITE`였습니다. 읽기도 됩니다. 쓰기는 모두 0~1ms 만에 `SecurityException: <함수> need Permission`으로 거절되었습니다(`setAmbientLightSwitch`, `setAmbientLightBrightness`, `setAmbientLightBrightnessColor`, `setReadingLightSwitch`, `setHvacFanSpeed`, `setHvacSeatVentilation`). 다시 읽은 값은 바뀌지 않았습니다 |
| 고치기 전, Gleo로 무대 열기 6초 | `stage open, driving [vehicle, simulator]`였고 화면 안내는 없었습니다. 앰비언트 밝기 쓰기 41번, 색 19번이 모두 같은 예외로 실패했습니다. 닫을 때는 바뀐 값이 없어 `restored all=true`였습니다 |
| 고친 뒤, Gleo로 무대 열기 | 대상마다 처음 한 번만 SDK를 불렀고(`the car does not let this app control it: SecurityException: …`), 그 뒤로는 `not sent`로 건너뛰었습니다. 화면에 "차가 허용하지 않은 기능은 화면에서만 반응해요."가 나왔고, 실내 시뮬레이터는 그대로 반응했습니다 |
| 무대를 닫았다가 다시 열기 | 닫자 안내가 사라졌습니다. 다시 연 무대는 대상마다 한 번씩 SDK를 다시 불러 거절을 확인한 뒤 건너뛰었고, 안내도 다시 나왔습니다. 처음 만든 빌드는 거절을 앱이 끝날 때까지 기억해 두 번째 무대에서 SDK를 부르지 않았지만, 실차는 권한이 아닌 이유로 거절할 수도 있어 무대마다 다시 시도하게 바꿨습니다 |
| 무대를 연 채 강제 종료한 뒤 다시 시작 | 복원 기록 폴더(`files/stage`)가 비어 있었고, 다시 시작해도 복원 로그와 안내가 없었습니다. 거절된 첫 쓰기 직전에 적은 원래 값은 바로 지우기 때문입니다 |
| 권한을 되돌린 빌드 | `scripts/regress.sh`의 다섯 시나리오가 모두 PASS였고, `does not let this app` 로그는 없었습니다 |

- 이 빌드는 lint가 `MissingPermission`(제어 함수마다 필요한 권한이 매니페스트에 없음)으로 실패합니다. 시험할 때만 `assembleDebug`로 만들고, 확인한 뒤 매니페스트를 되돌렸습니다.
- 실차에서 권한이 다른 방식으로 거절될 수도 있습니다(예: 오류 코드). 그때는 `isControlDenied()`에 그 형태를 더합니다.
- 설치한 개발 빌드에서 `adb shell pm revoke --user 10 com.carstage.app pleos.car.permission.CONTROL_CAR_LIGHTS`는 `SecurityException: … is not a changeable permission type`으로 거절됩니다(2026-11-07). Pleos 차량 권한은 설치 뒤에 거둘 수 없으므로, 제어 거절은 이 절처럼 매니페스트에서 권한을 빼고 빌드해 시험합니다.

## 무대 글로우 확인 (2026-10-24)

무대가 열린 동안 화면 가장자리가 앰비언트 조명 색으로 빛나는 무대 글로우([10](10-ui-ux-plan.md) 3장)를 넣고, 효과음을 끈 채 녹음 재생으로 확인했습니다.

| 확인 | 결과 |
|---|---|
| car-2 재생 14초 | 시뮬레이터 앰비언트가 `#6600FF`, 밝기 82%일 때 화면 네 가장자리가 보라색으로 빛났습니다. 가운데의 글자와 버튼은 그대로 읽혔습니다 |
| duel-two-songs 재생과 Gleo `START_BATTLE` | 운전석 차례에는 가장자리가 파랑이었습니다. 발표 중(`#2060FF`, 밝기 80%, 운전석 독서등 켜짐)에는 왼쪽 가장자리가 오른쪽보다 조금 밝았습니다(같은 높이의 화소 `(59,62,119)`와 `(55,58,107)`). 색과 세기는 처음 구현에서 0.6초 전환으로, 고친 뒤에는 약 1초에 걸쳐 따라가는 스프링으로 바뀌어, 발표의 좌우 번갈이도 깜빡이지 않고 부드럽게 넘어갔습니다 |
| CPU, 처음 구현 (car-1 재생 8~28초) | 글로우가 있는 빌드는 20초에 507틱(코어 하나의 25.4%), 글로우를 그리는 줄만 막은 빌드는 477틱(23.9%)이었습니다(한 번씩). 처음 구현은 프레임마다 그라디언트 네 개를 새로 만들었고, 글로우가 화면 전체 레이어를 다시 기록하게 했습니다 |
| CPU, 고친 뒤 (car-1 재생 8~68초, 20초 구간 세 개) | 글로우를 자기 그래픽 레이어에 그리고 가장자리 마스크를 크기마다 한 번만 만들어 색만 입히게 바꾼 뒤 다시 쟀습니다. 글로우가 있는 빌드는 세 번 재생해 27.9/24.8/22.9%, 26.7/23.6/22.7%, 22.1/21.9/21.4%(평균 약 23.8%)였습니다. 글로우를 막은 빌드는 두 번 재생해 23.6/24.1/24.2%, 19.3/18.4/20.1%(평균 약 21.6%)였습니다. 같은 빌드도 재생마다 4%p 넘게 달라지므로, 글로우가 더한 몫은 코어 하나의 약 2%p로 봅니다 |

- 글로우가 있는 빌드는 9구간 중 2구간(두 재생의 첫 구간, 27.9%와 26.7%)에서 코어 하나의 25%를 넘었습니다. 4코어 전체로는 5~7%입니다. Pleos의 "정차 중 CPU 25% 이하"가 코어 하나 기준이면 이 연출은 기준에 걸릴 수 있으므로, 실차에서 다시 잽니다(아래 "남은 검증"). 코어 하나 기준이라면 화면 갱신을 줄이는 작업(애니메이션 프레임 수 줄이기, 실내 그림과 글로우 중 하나만 움직이기)을 먼저 합니다.
- 무대가 닫혀 있으면 글로우를 그리지 않습니다(알파 합이 0.005 미만이면 그리지 않음).

## 드럼롤 좌우 번갈이 확인 (2026-10-24)

대결 발표의 소리를 바꿨습니다([11](11-no-control-plan.md) 5장 "소리").
- 드럼롤은 독서등이 번갈아 켜지는 것과 같은 프레임에 운전석(왼쪽)과 조수석(오른쪽) 쪽으로 옮겨 갑니다.
- 차례마다 조금씩 빨라집니다(1.0배에서 1.15배).
- 조용한 구간에서는 가운데로 돌아옵니다.
- 팡파레는 승자 쪽에서 나옵니다(무승부는 가운데).

재생 중인 스트림의 좌우 음량은 `SoundPool.setVolume`으로 바꾸고(먼 쪽 25%), 빠르기는 `setRate`로 바꿉니다. 같은 프레임에 효과음을 시작하고 바로 옮기므로, 실제 소리는 처음부터 옮긴 쪽에서 납니다. SoundPool은 아직 시작하지 않은 스트림에 준 음량과 빠르기도 시작할 때 씁니다. 효과음을 끈 채 duel-two-songs 재생과 Gleo `START_BATTLE`로 확인했습니다. 끈 동안에는 바꾸는 신호가 `(muted)`를 붙인 줄로 남습니다.

| 발표 시작부터 | 로그 |
|---|---|
| 0.98초 | `sound DRUMROLL muted for 4180 ms`, `sound change DRIVER at 1.00x (muted)` |
| 1.96, 2.73, 3.33, 3.80, 4.19, 4.57초 | `PASSENGER at 1.02x`, `DRIVER at 1.05x`, `PASSENGER at 1.08x`, `DRIVER at 1.10x`, `PASSENGER at 1.13x`, `DRIVER at 1.15x`(모두 `sound change … (muted)`). 간격이 1.0초에서 0.38초로 줄어 독서등 번갈이와 같습니다 |
| 4.95초 | `sound change CENTER at 1.15x (muted)`(조용한 구간. 드럼롤이 더 길게 남아도 음 높이가 떨어지지 않게 마지막 빠르기를 유지) |
| 5.93초 | `sound FANFARE muted for 1940 ms`, `sound change PASSENGER at 1.00x (muted)`(승자가 조수석) |
| 15.5초 | `stage closed, restored all=true` |

- 드럼롤 파일은 약 3.99초입니다(ffprobe, 로그의 4180 ms는 끝 여유 200 ms를 더한 값). 빠르기를 1.25배까지 올리면 번갈이가 끝나기 약 0.3초 전에 끝나 마지막 차례가 거의 비므로, 1.15배로 정했습니다. 이때는 약 0.2초 전, 마지막 차례의 중간쯤에 끝납니다(`DuelTest`가 계산으로 확인). 오디오 포커스와 환호 무시 구간은 원래 길이만큼 잡으므로 실제 소리보다 조금 더 깁니다.
- 에뮬레이터의 오디오 출력은 모두 2채널입니다(`dumpsys media.audio_flinger`의 `Channel mask: 0x00000003 (front-left, front-right)`). 효과음 파일은 모노지만 `SoundPool`이 좌우 음량을 따로 주므로 좌우로 옮길 수 있습니다. 에뮬레이터 이미지는 Android 14라서 `setRate`가 적용됩니다. Android 11~13의 SoundPool은 빠른 출력 경로를 써서 빠르기 변경이 조용히 무시되고 1.0배로 남습니다.
- 소리를 끈 시험이라, 실제 스트림의 좌우와 빠르기가 바뀌는 것은 아직 귀로 확인하지 않았습니다. 에뮬레이터의 소리는 맥 스피커로 나오므로, 들어 보는 시험은 발표 직전에만 효과음을 켜서 드럼롤과 팡파레(약 7초)만 나오게 합니다.
- 실차에서는 효과음 용도의 소리가 OEM이 정한 버스로 나갑니다. 차가 효과음을 모노로 섞으면 좌우가 들리지 않고 드럼롤이 약 4 dB 작아지며, 운전석 스피커로만 내면 조수석 차례가 약 12 dB 작아집니다. 실차에서 확인합니다(아래 "남은 검증").

## 대결 발표의 화면 좌우 빛 확인 (2026-10-25)

대결 발표 중에는 무대 글로우가 좌우를 맡게 했습니다. 독서등이 켜진 쪽 가장자리가 그쪽 색(운전석 파랑 왼쪽, 조수석 빨강 오른쪽)으로 분명히 빛나고(알파 0.6), 반대쪽(0.06)과 위아래(평소의 30%)는 어두워집니다. 발표 중의 좌우는 약 0.6초 스프링으로 따라가(가장 짧은 0.38초 차례 안에 약 90%), 번갈이가 보이면서도 깜빡이지 않습니다. 발표가 아닐 때는 모든 가장자리가 약 1초 스프링으로 함께 움직입니다. 발표가 끝나도 두 독서등이 꺼질 때까지는 좌우 모드를 유지해, 진 쪽이 잠깐 번쩍이지 않게 했습니다. 효과음을 끈 채 duel-two-songs 재생과 Gleo `START_BATTLE`로, 발표 시작 뒤 세 시점의 화면을 찍어 같은 높이의 가장자리 화소를 비교했습니다.

| 발표 시작부터 | 왼쪽 가장자리 | 오른쪽 가장자리 | 위 가장자리 |
|---|---|---|---|
| 약 1.6초(운전석 차례) | `(30,65,153)` 파랑 | `(41,28,32)` 어두움 | `(31,35,53)` |
| 약 2.4초(조수석 차례) | `(28,31,43)` 어두움 | `(160,39,41)` 빨강 | `(50,32,37)` |
| 약 6.6초(승자 조수석) | `(28,31,43)` 어두움 | `(160,39,41)` 빨강 | `(57,30,33)` |

- 리뷰를 반영해 발표 중 좌우 스프링을 약 0.5초에서 약 0.6초로 늦춘 뒤 다시 찍어도 거의 같았습니다(운전석 차례 왼쪽 `(30,65,153)`, 조수석 차례 오른쪽 `(156,39,40)`, 승자 오른쪽 `(160,39,41)`).
- 가운데의 대결 카드("결과를 발표하겠습니다")와 버튼은 그대로 읽혔습니다.
- 무승부는 녹음으로 만들기 어려워 단위 테스트(`GlowLookTest`)로만 확인했습니다. 마무리에 두 독서등이 모두 켜지므로 왼쪽 파랑, 오른쪽 빨강으로 나뉩니다.

## MC 음성 확인 (2026-10-25)

2026-10-31: Pleos TextToSpeech SDK로 바꿔 에뮬레이터에서도 말합니다. 아래 "Pleos TTS로 MC 음성 확인"을 봅니다.

대결 발표의 MC 멘트를 Android 표준 `TextToSpeech`로 말하게 했습니다(Pleos TTS SDK는 "Coming Soon"). 한국어를 말하는 엔진이 없으면 로그만 남기고 화면 문구로 대신합니다.

| 확인 | 결과 |
|---|---|
| 등록된 TTS 엔진(`cmd package query-services --user 10 -a android.intent.action.TTS_SERVICE`) | `ai.pleos.playground.caas/.service.speech.SpeechService` 하나입니다. 시스템 앱(`/system_ext/app/AmbientAiService`)이고 `exported=true`, `permission=null`입니다. Google TTS 같은 다른 엔진은 없고, 기본 엔진 설정(`settings get secure tts_default_synth`)은 `null`입니다 |
| 무대를 열 때 `TextToSpeech` 초기화(엔진은 무대가 열린 동안만 잡음) | 매니페스트에 `<queries>`(TTS_SERVICE)를 넣고도 `status -1`(ERROR)로 끝났습니다. 로그: `voice: no text-to-speech engine found (status -1); the screen's words stand alone`. `TextToSpeech` 태그의 연결 시도 로그가 없었고, `dumpsys activity services ai.pleos.playground.caas`에는 사용자 10(앱)의 연결 기록이 없었습니다(사용자 0의 `SpeechService`만 실행 중). 이 엔진의 인텐트 필터에는 동작(action)만 있고 기본 카테고리(`android.intent.category.DEFAULT`)가 없습니다(`dumpsys package ai.pleos.playground.caas`). 그래서 연결에 실패했다기보다 `TextToSpeech`가 엔진을 찾지 못한 것으로 봅니다. 그렇다면 앱 쪽 코드로는 바꿀 수 없습니다 |
| Lab `--es probe voice` | 세 줄 모두 `voice skipped, no text-to-speech engine (status -1): "…"`이었고 소리는 나지 않았습니다(엔진을 다시 찾게 바꾼 뒤에는 `found`가 붙음) |
| 효과음을 끈 채 duel-two-songs 재생과 Gleo `START_BATTLE` | 발표 시작과 함께 `voice "결과를 발표하겠습니다" (muted)`, 팡파레 1.79초 뒤 `voice "승자는 조수석! 53점입니다" (muted)`가 남았습니다. 결과 화면(`duel done`)까지는 2.2초가 남습니다 |

- 그래서 에뮬레이터에서는 말하는 경로(오디오 포커스, 덕킹, 환호 판정 버림, 끝난 뒤 포커스 반납)를 확인하지 못했습니다. 단위 테스트는 어느 순간에 어떤 문장을 말하는지까지만 확인합니다.
- Pleos 엔진의 내부는 SDK 라이선스(디컴파일·리버스 엔지니어링 금지)에 따라 더 들여다보지 않았습니다. 실차에서 쓸 수 있는 엔진이 있는지는 실차에서 확인합니다.

## 승자 쪽 온도 확인 (2026-10-25)

대결 발표의 마무리에서 승자 쪽(무승부는 양쪽) 온도 설정을 무대 시작 때보다 2도 낮췄다가 발표가 끝나면 되돌리게 했습니다([04](04-karaoke-plan-v2.md) "좌우 연출 레이어" 5). 효과음을 끈 채 duel-two-songs 재생과 Gleo `START_BATTLE`로 확인했습니다(승자는 조수석).

| 확인 | 결과 |
|---|---|
| capability(`HVAC_TEMPERATURE_SET`) | areaId 1(운전석)과 4(조수석), 15.0~30.0°C, `configArray [150,300,5,580,880,10]`(섭씨 0.1도 단위로 15.0~30.0, 0.5도 간격으로 보임). 무대를 열 때 두 자리 모두 22.0으로 읽혔습니다 |
| 마무리 시작 | `setPropertyAsyncValue … HVAC_TEMPERATURE_SET, areaId = 4, value = 20.0` → `onSuccess`(시트 통풍 3단과 같은 순간) |
| 발표 끝 | `areaId = 4, value = 22.0` → `onSuccess`(마무리 직전에 읽은 값으로 되돌림) |
| 무대 닫기 | `areaId = 4, value = 22.0` → `onSuccess`, `stage closed, restored all=true` |
| Virtual Console(`scripts/vconsole.sh state hvac`) | 앞, 마무리 1초 뒤, 끝난 뒤 모두 `driver.temp_c 22.0`, `passenger.temp_c 22.0`, `dual_on false`였습니다. 앰비언트 색·밝기처럼 쓰기에 성공 응답만 하고 값은 바뀌지 않았습니다 |
| 실내 시뮬레이터 | 좌석 이름이 "조수석 -2°"로, 설명 줄이 "온도 운전석 그대로 · 조수석 2.0도 낮춤"으로 바뀌었습니다 |

- 듀얼 공조가 꺼진 차(`dual_on false`)에서 한쪽만 바꾸면 다른 쪽이 따라갈 수 있습니다. 에뮬레이터는 값을 바꾸지 않아 확인하지 못했고, 실차에서 확인합니다.
- 4초 동안 공기 온도는 크게 바뀌지 않습니다. 이 연출은 차의 공조 화면에 보이는 설정값으로 전합니다.
- 공조 전원과 상관없이 성공 응답이 왔습니다(같은 에뮬레이터에서 풍량 쓰기는 공조 전원이 꺼져 있으면 NOT_AVAILABLE로 실패). 실차에서 공조 전원이 꺼진 상태로 온도 설정을 받는지, 받으면서 공조를 켜는지 확인합니다.
- 되돌리기는 혼합기가 맡습니다. 실제로 나간 차이를 기억해, 발표가 끝나면 시작 때로 되돌리기를 규칙이 내보낼 때까지 계속 요청합니다. 끝나는 순간 성에 제거·와이퍼 읽기가 실패해도 다음 읽기에 되돌리고, 규칙이 낮추기를 막았으면 되돌리기도 보내지 않습니다(10-25 피날레 환호 바람 리뷰에서 바꿈, 아래 "피날레 환호 바람 확인").

## 피날레 환호 바람 확인 (2026-10-25)

모든 등급의 피날레에서 0.5~2초에 풍량을 무대 시작 때보다 한 단 올렸다가 되돌리게 했습니다([04](04-karaoke-plan-v2.md) "정차 중 연출 순서"). 풍량 명령은 온도처럼 무대 시작 때 값에서의 단 차이(`CabinCommand.FanStep`)로 바꿨습니다. 효과음을 끈 채 two-songs-gap 재생으로 첫 곡의 피날레를 확인했습니다(`song scored 6 (BASIC)`).

| 확인 | 결과 |
|---|---|
| Virtual Console(`scripts/vconsole.sh state hvac`의 `fan_speed`, 공조 전원 켜짐) | 피날레 전 3, 피날레 1.2초 뒤 4, 3.7초 뒤 3, 무대를 닫은 뒤 3이었습니다. 앰비언트 색이나 온도와 달리 풍량은 콘솔 값에 반영됩니다 |
| SDK 로그(`HVAC_FAN_SPEED`, areaId 5) | `value = 4` → `onSuccess`, 1.46초 뒤 `value = 3` → `onSuccess`, 무대 닫기에 `value = 3` → `onSuccess` |
| 실내 시뮬레이터 | 설명 줄이 "풍량 1단 올림"으로 바뀌었다가 "그대로"로 돌아왔습니다 |

- 공조 전원이 꺼진 차에서는 풍량 쓰기가 실패합니다(2026-10-21, 위 "에뮬레이터에서 확인된 동작"). 앱은 공조 전원을 켜지 않으므로, 그때는 조명과 박수만으로 피날레를 합니다. 실패는 대상마다 한 번만 로그에 남습니다.
- 처음 구현은 되돌리기(차이 0)를 안전 규칙에서 빼서 늘 보냈는데, 리뷰에서 "올리기가 성에 제거로 막혔는데도 되돌리기가 나가 성에 제거 중에 공조를 씀"이 지적되었습니다. 그래서 되돌리기를 혼합기로 옮겼습니다. 혼합기는 실제로 나간 차이를 기억해 바꾼 적 없는 대상에는 되돌리기를 보내지 않고, 바꾼 대상은 시작 때로 돌아갈 때까지 계속 요청합니다.
- Virtual Console로 성에 제거를 켠 채 같은 재생을 하자 풍량 쓰기가 한 번도 나가지 않았고(`fan_speed` 3 그대로), 무대를 닫을 때 `the command rules held back 36 commands in this stage`가 남았습니다. 바람 1.5초 동안 막힌 올리기를 다시 요청한 횟수입니다. 시험 뒤 성에 제거는 껐습니다.
- 무대 중에 Virtual Console로 풍량을 6으로 바꾼 뒤의 피날레는 6 → 7 → 6이었고, 무대를 닫은 뒤에는 무대 시작 때 값 3으로 복원되었습니다. 차량 출력이 바람을 내기 직전에 지금 풍량을 읽어 기준으로 삼기 때문입니다.
- 두 번째 리뷰를 반영한 뒤에는 되돌린 풍량을 무대를 닫을 때 다시 쓰지 않습니다. 같은 재생에서 SDK 로그의 풍량 쓰기가 `value = 4`, `value = 3` 두 번뿐이었고(이전에는 닫을 때 `value = 3`이 한 번 더 나감), 복원 기록(`files/stage`)도 비어 있었습니다. 성에 제거가 그사이 풍량을 올렸어도 닫기가 그 바람을 낮추지 않기 위해서입니다. 바꾸기 직전 값을 읽지 못하면 그 변화는 건너뜁니다.
- Virtual Console에서 `set /vehicle/hvac fan_speed=7`은 앱이 꺼져 있을 때 바로 다시 읽어도 3이었습니다(자동 공조가 되돌리는 것으로 보임). 무대 중에는 6이 반영된 적이 있어, 콘솔로 풍량을 바꾸는 시험은 결과가 일정하지 않습니다.
- 풍량을 바꾸는 동안 Virtual Console의 `auto_on`은 계속 `true`였습니다. 실차는 수동 풍량에 공조 AUTO를 끌 수 있으므로 실차에서 확인합니다.

## 야간 밝기 배려 확인 (2026-10-25)

밤에는 앰비언트 밝기를 0~40% 범위로 줄여 보내게 했습니다([07](07-convenience-features.md) "야간 밝기 배려"). 밤인지는 Android 표준 `NIGHT_MODE`(0x11200407)를 `CarPropertyManager.registerCallback`(ON_CHANGE)으로 받아 정합니다.

- 권한 `android.car.permission.CAR_EXTERIOR_ENVIRONMENT`는 일반 권한입니다(`dumpsys package`의 `prot=normal`). 앱 시작 때 `listening for night mode`, `night mode off`가 남았습니다. 에뮬레이터의 값은 꺼짐(낮)입니다.
- Virtual Console에는 `NIGHT_MODE`를 바꾸는 항목이 없습니다. 전조등을 켜도(`scripts/vconsole.sh set /vehicle/lights feature=headlamp_mode value=LowBeam`, 콘솔 속성 `EXTERIOR_LIGHTS_MODE`) `NIGHT_MODE`는 꺼짐 그대로였습니다. 에뮬레이터는 user 빌드라 `cmd car_service get-property-value`도 `requires non-user build`로 거절됩니다. 그래서 밤 연출은 개발 빌드의 `--es night on|off|car`로 확인했습니다.
- 효과음을 끈 채 car-2를 재생하며 Virtual Console의 `body.ambient_light.brightness_pct`를 0.25초마다 읽었습니다.

| 확인 | 결과 |
|---|---|
| 밤(`--es night on`) | 표본 89개. 무대 중 24~40%(24, 25, 32, 33, 37, 40)였고, 40%를 넘은 적이 없습니다. 20%는 무대 전후의 원래 값입니다 |
| 낮(`--es night off`) | 표본 88개. 무대 중 40~100%(40, 63, 71, 72, 79, 96, 100)였습니다 |
| 화면 | 밤에 무대가 열린 동안 버튼 아래에 "밤이라 눈부시지 않게 조명을 낮춰서 비출게요."가 보였고, 실내 시뮬레이터의 설명도 "밝기 36%"였습니다. 밤이어도 무대를 닫은 첫 화면에는 이 안내가 없었습니다 |
| 로그 | `night by a test: the stage's light dims to 40% at most`, `day by a test: the stage's light uses its full range` |

- `scripts/check.sh --device`와 `scripts/regress.sh`(5개)가 통과했습니다.
- 노래 없이 Gleo로 무대를 열면 밤에는 밝기가 8%(낮의 바닥 20%의 40%), 낮에는 20%였습니다. 이것으로 `scripts/regress.sh`에 `night` 시나리오를 넣었습니다(시험 명령으로 밤을 만들고, 무대가 열린 동안 콘솔 밝기를 8번 읽어 40%를 넘지 않고 20%보다 낮은 값이 있는지 확인). 리뷰를 반영한 뒤 6개 모두 통과했고, 밤 시나리오의 밝기는 8번 모두 8%였습니다.
- Virtual Console에서 `set /vehicle/body ambient_brightness=20`을 보낸 직후에도 콘솔 상태는 100 그대로였습니다. 그런데 다음 무대가 SDK로 읽은 원래 값은 20이었고, 무대를 닫아 복원한 뒤에는 콘솔도 20을 보였습니다. 콘솔의 앰비언트 밝기는 앱이 마지막으로 쓴 값을 보여 주는 것으로 보이므로, 무대 전후 값의 비교에는 SDK 읽기(`--es probe vehicle`)를 씁니다.
- 실차가 `NIGHT_MODE`를 알려 주는지, 40%가 어두운 차 안에서 알맞은지는 실차에서 확인합니다.

## 화면 디자인 토큰 확인 (2026-10-25)

첫 화면에 Pleos 글자 체계와 색 토큰을 넣고([10](10-ui-ux-plan.md) 5장), 효과음을 끈 채 대기, 무대 열림, 결과 카드, 대결 차례, 충전 끝 질문 화면을 캡처했습니다(`--es demo on`으로 개발용 표시를 숨김).

- 화면 밀도는 160 dpi라 sp 값이 그대로 픽셀입니다(캡처 2560×1440에서 앱 영역 약 1700px 폭).
- 한국어 줄바꿈: Compose `LineBreak.Paragraph`는 단어 나누기가 기본값이라 "곡이 끝 / 났는데"처럼 음절 사이에서 줄이 바뀌었습니다. `LineBreak.WordBreak.Phrase`를 주자 "곡이 / 끝났는데", "\"곡 끝내기\"를 / 눌러 주세요"처럼 띄어쓰기에서만 바뀌었습니다(Android 14, 시스템 언어 한국어). `LineBreak.Heading`은 처음부터 Phrase입니다. 이 나누기는 글자의 언어를 따르므로, 시스템 언어가 다른 차에서도 같게 나오도록 글자를 한국어(`ko-KR`)로 표시합니다.
- 충전 끝 카드와 대결 차례 카드가 함께 떠도 오른쪽 칸 안에 들어갔습니다. 대결 카드와 결과 카드는 함께 뜨지 않으므로 카드는 많아야 두 개입니다. 충전 끝 질문, 끝난 대결 카드, 버튼 둘, 두 줄 안내를 더하면 계산으로 약 1116dp라 남는 높이가 30dp쯤이고, 안내가 여러 문장 쌓이면 넘칩니다(화면을 직접 캡처하지는 않았습니다). 그래서 오른쪽 칸은 높이를 넘을 때만 스크롤되게 했습니다.
- 로그: `test: demo on: development displays hidden`(CarStageLab), 결과 카드 화면의 `song scored 3 (BASIC) over 13 s`, 충전 끝 화면의 `charging stopped: asking whether this song is the last`(CarStage).
- 개발자 Lab 버튼과 실내 그림 아래 값 줄은 개발 빌드에서만 보이고, `--es demo on`이면 개발 빌드에서도 숨습니다. 출시 빌드는 늘 숨깁니다. (2026-10-31: 값 줄은 모든 빌드에 보이는 상태 아이콘 줄로 바뀌었습니다. 아래 "상태 아이콘 확인")
- 안내 문구는 처음에 모두 오류 색이었습니다. 정보 안내("지난 무대에서 바뀐 차량 설정을 원래대로 되돌렸어요.")도 빨갛게 보였습니다. 같은 날 종류를 나눈 뒤([10](10-ui-ux-plan.md) 5장 "안내 종류"), 무대가 열린 채 `--es night on`이면 "밤이라 눈부시지 않게 조명을 낮춰서 비출게요."가 파랑으로 보였습니다. 기어 D에서 Gleo로 무대를 열면 앱 화면은 플랫폼이 가리고, P로 돌아오자 "정차 중에만 무대를 열 수 있어요."가 노랑으로 보였습니다.

## 재생 중인 곡 읽기 확인 (2026-10-25)

다른 음악 앱이 무엇을 틀고 있는지(앱, 곡 정보, 재생 상태와 위치)를 읽을 수 있는지 Lab의 "재생 중인 곡" 진단(`--es probe nowplaying`)으로 확인했습니다. 이 진단을 쓸 때는 제품이 재생 여부만 알았습니다(`MusicPlayback`). 이후 첫 화면 미니 플레이어가 같은 방식을 씁니다(아래 "첫 화면 미니 플레이어 확인"). 진단은 개발 빌드에만 있습니다.

- 다른 앱의 MediaSession을 바로 읽는 권한 `MEDIA_CONTENT_CONTROL`은 `signature|privileged`라 일반 앱은 받을 수 없습니다. 대신 알림 접근(`NotificationListenerService`)을 사용자가 허용하면 `MediaSessionManager.getActiveSessions`로 읽을 수 있습니다.
- 설정 화면: `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`가 Pleos 설정의 "앱 > 알림 액세스"(`CarSettingActivities$NotificationAccessActivity`)로 열립니다. CarStage와 Gleo AI(이미 켜짐)가 목록에 있고, 스위치를 켜면 "연락처 이름과 수신 메시지 텍스트 등 개인정보를 포함한 모든 알림을 읽을 수 있게 됩니다"라는 확인 창이 뜹니다. 허용하면 앱을 다시 설치해도 유지됩니다.
- 음악 앱: 이 이미지에는 Google Play 스토어와 Google Play 서비스가 없어 YouTube Music은 쓸 수 없습니다. VLC 3.7.1(공식 arm64 APK, sha256과 VideoLAN 서명 확인)을 설치했습니다. 사용자 10의 저장소에는 adb로 파일을 넣을 수 없어서(`Permission denied`, user 빌드라 `adb root` 불가) 맥에서 HTTP로 띄우고 `http://10.0.2.2:<포트>/...`를 VLC에 `VIEW` 인텐트로 보냈습니다. m4a는 VLC가 받기만 하고 재생하지 않았고(`invalid next index`), ADTS AAC(`afconvert -f adts -d aac`)로 바꾸니 재생했습니다. 파이썬 `http.server`가 Range 요청을 지원하지 않아서로 보입니다. VLC를 링크로 처음 열면 Pleos 홈이 창을 바로 닫고 프로세스도 종료되므로, 먼저 일반 실행으로 띄운 뒤 링크를 보냅니다.

| 확인 | 결과 |
|---|---|
| 알림 접근 없음 | `notification access granted=false`. 세션을 읽지 않고 끝납니다 |
| VLC 재생 중 | `active sessions: 1`, `org.videolan.vlc: playing at 4s (updated 0s ago) song: title="car-1" display_title="car-1" artist=<empty> length=82s`. 태그가 없는 파일이라 제목은 파일 이름이고, 아티스트는 키는 있지만 값이 비어 있습니다 |
| 일시정지와 재생(`KEYCODE_MEDIA_PAUSE`, `PLAY`) | `state: paused at 9s` → `state: playing at 9s`가 바로 왔습니다 |
| 정지(`KEYCODE_MEDIA_STOP`) | `state: stopped at 0s` 뒤에 `sessions changed: none`. 세션이 목록에서 빠집니다 |
| 관찰 도중 알림 접근을 끔(`cmd notification disallow_listener`) | 끈 뒤에도 정지와 `sessions changed: none`이 왔고, 끝에 `watch end: access granted=false`가 남았습니다 |

- 멜론 같은 상용 앱이 곡 정보를 얼마나 채우는지, Pleos 앱 심사가 알림 접근을 허용하는지는 아직 모릅니다.

## 첫 화면 미니 플레이어 확인 (2026-10-25)

첫 화면에 다른 앱의 음악을 조작하는 카드를 넣고([07](07-convenience-features.md) "첫 화면 미니 플레이어"), VLC로 시험 곡 재생 목록을 틀어 확인했습니다. 알림 접근 서비스는 제품 코드(`audio.NowPlayingListener`)로 옮겨서, 컴포넌트 이름이 바뀐 뒤 처음에는 접근이 꺼진 상태였습니다.

| 확인 | 결과 |
|---|---|
| 알림 접근 없음 | 카드에 "음악 재생 중", 버튼 셋, "곡 정보 보기"가 보였습니다 |
| 권한 없이 버튼 | `now playing: pause (no session; media key sent)`(처음 빌드의 문구는 `pause by media key`) → MediaSessionService가 `KEYCODE_MEDIA_PAUSE`를 VLC(userId=10)에 보냈고 VLC가 멈췄습니다. 재생, 다음 곡, 이전 곡도 같았습니다 |
| "곡 정보 보기" | 설정의 알림 액세스 화면이 열렸고, 켜고 뒤로 가자 `notification access granted`, `following org.videolan.vlc`와 함께 카드가 재생 중인 곡의 제목, 가수, 위치(0:26 / 7:08)로 바뀌었습니다 |
| 권한 있을 때 버튼 | `pause org.videolan.vlc` → 카드 아이콘이 재생으로 바뀜, `next org.videolan.vlc` → "Big Rock / Kevin MacLeod / 0:03 / 3:50" |
| 무대를 연 상태 | 카드, 안내, "무대 닫기", "곡 끝내기", "대결 시작"이 겹치지 않고 들어갔습니다. 알림 접근이 없어도 "곡 정보 보기"는 숨습니다(설정 화면이 CarStage를 덮으면 무대가 닫히므로) |
| CarStage가 앞에 있는 채로 알림 접근을 끄고 켬(`cmd notification disallow_listener`, `allow_listener`) | 끄자 `notification access not granted`, `following no session`과 함께 카드가 "음악 재생 중 / 곡 정보 보기"로 바뀌었고, 켜자 `notification access granted`, `following org.videolan.vlc`와 함께 곡 정보가 돌아왔습니다. 리스너 서비스의 연결·끊김(`onListenerConnected`, `onListenerDisconnected`)으로 알아챕니다 |

- VLC가 HTTP로 받는 ADTS AAC는 처음에 길이를 7:08로 알렸다가, 다시 재생하자 3:21로 고쳐 알렸습니다. 길이는 음악 앱이 알린 값을 그대로 씁니다.
- `scripts/regress.sh`: 전체로 돌리면 `probes`가 실패합니다. `recovery` 바로 뒤의 연출 진단이 `stage restore: FAIL`(앰비언트 색·밝기, 독서등이 시작 전 값과 다름)을 냈고, 같은 순서로 main 빌드에서도 똑같이 실패했습니다. 따로 돌리면 통과합니다. 이번 변경과 관계없는 기존 문제이고 원인은 TBD입니다.

## 상태별 화면 확인 (2026-10-27)

첫 화면에 상태 줄, 목소리 반응 미터, 결과 카드의 점수 올라가기와 네 항목 막대, Gleo 음성 힌트를 넣고([10](10-ui-ux-plan.md) 8장 2번), 효과음을 끄고 `--es demo on`으로 개발용 표시를 숨긴 채 캡처했습니다.

- `scripts/vconsole.sh charging on`으로 충전을 켜고 녹음 재생으로 무대를 열자 상태 줄 한 줄에 "CarStage ● 듣고 있어요 충전 중 · 100%"가 보였습니다. 미터 색을 막대 바탕과 4.5:1이 되게 밝힌 뒤에는 파랑·보라 조명에서도 막대가 밝은 하늘색·연보라로 또렷했습니다.
- 녹음 재생(car-2)으로 무대를 열자 상태 줄에 초록 점과 "듣고 있어요"가 보였고, 미터는 1초 간격 캡처에서 가득 참과 83%를 오갔습니다. 미터 색은 그때의 조명 색(파랑, 보라)을 따라갔습니다. 녹음 재생도 마이크와 같은 입력 경로라 "듣고 있어요"로 보입니다.
- 재생 18초에 Gleo `FINISH_SONG`으로 곡을 끝내자 `song scored 69 (GOOD) over 17 s: participation 50, heat 9, response 0, completion 10`이 남았고, 0.4초 뒤 캡처의 점수는 42점, 2.4초 뒤는 69점이었습니다. 네 막대도 같은 비율로 차올랐습니다.
- Gleo 힌트를 처음에 한국어 둥근 따옴표(“ ”)로 쓰자, 시스템 글꼴에서 여는 따옴표도 닫는 모양(”)으로 보였습니다. 그래서 곧은 따옴표(")를 씁니다.
- 결과 카드가 뜬 채 무대가 열려 있으면 오른쪽 칸이 거의 꽉 찹니다(상태 줄부터 마지막 버튼까지 약 1000dp). 미니 플레이어까지 뜨면 넘쳐서 스크롤됩니다(같은 날 미니 플레이어를 왼쪽 칸으로 옮겨 해결, 아래 "미니 플레이어를 왼쪽 칸으로 옮긴 확인"). 미니 플레이어는 다른 앱의 음악이 맥 스피커로 나와야 떠서 이 화면은 캡처하지 않았고, 크기는 계산했습니다([10](10-ui-ux-plan.md) "2번 PR 구현").
- CPU(car-2 재생 10초 뒤부터 3초 간격 5번, `top -p`, 코어 하나 기준, 빌드마다 한두 번): 처음 미터(거의 매 프레임 넘김)는 평균 약 34%, 미터를 뺀 같은 빌드는 약 31%였습니다. 0.02(8dp) 이상 움직일 때만 넘기고 막대를 자기 그래픽 레이어에 둔 뒤에는 평균 약 32%였습니다. 같은 날 같은 방법으로 main은 약 31%, 디자인 토큰 전 빌드는 약 34%였습니다. 빌드 사이의 차이는 같은 빌드를 다시 잴 때의 흔들림(4%p 넘게, 위 "무대 글로우 확인")보다 작아서, 미터가 CPU를 늘린다고 볼 근거는 없습니다. 10-24에 잰 22~25%보다 모두 높은 것은 그날의 에뮬레이터 조건으로 봅니다. 다만 코어 하나 기준이면 약 30%는 Pleos 기준 25%를 넘으므로, 기준이 어느 쪽인지가 더 중요해졌습니다(4코어 전체로는 약 8%). 스레드별로는 메인 스레드 약 18%, RenderThread 약 10%, 녹음 재생 스레드 약 4%였습니다.
- "잠시 안 들려요"(디지털 무음): 상태는 화면 아래 무음 안내와 같은 신호(분석기가 디지털 무음을 약 3초 세면 남기는 `only digital silence for about 3 s`)를 따릅니다. `microphone silenced by the system`(Android의 녹음 무음 알림)은 로그만 남기는 다른 신호입니다. 같은 날 미니 플레이어를 옮기며 찍은 화면에서 호스트 마이크가 0만 주자(아래), 상태 줄이 노랑 점과 "잠시 안 들려요"로 바뀌고 화면 아래에 노랑 무음 안내가 붙는 것을 확인했습니다. 로그는 `microphone: only digital silence for about 3 s`, 닫을 때 `348 silent (longest run 348)`였습니다.
- 같은 날 에뮬레이터를 다시 켠 뒤 호스트 마이크가 0만 주었습니다. `scripts/run-emulator.sh`는 `host microphone enabled`를 남겼고 `adb emu avd hostmicon`을 다시 보내도 `--es probe smoke`가 `rms=-100.0 peak=-84.3 dBFS: no host audio`로 실패했습니다. 맥 쪽(macOS의 마이크 권한이나 입력 장치) 문제로 보고, 녹음 재생을 쓰는 시험(`regress.sh`의 replay, night)에는 영향이 없습니다.

## 미니 플레이어를 왼쪽 칸으로 옮긴 확인 (2026-10-27)

미니 플레이어를 오른쪽 칸에서 왼쪽 칸의 실내 그림 아래로 옮기고([10](10-ui-ux-plan.md) "2번 PR 구현"), 시험 재생 앱을 무음만 내게 잠시 바꿔 설치한 뒤 캡처했습니다(맥 스피커로 소리가 나지 않게 `PlaybackService.sample`이 늘 0을 돌려주도록 고쳐 빌드하고, 확인 뒤 원래 소스로 다시 빌드해 설치. 저장소의 `tools/`는 바꾸지 않음). 무음이어도 Android는 재생 중(`music playing=true`)으로 알려 카드가 뜹니다.

- 대기 화면: 왼쪽에 실내 그림과 그 아래 "음악 재생 중" 카드(이전 곡, 재생·일시정지, 다음 곡, "곡 정보 보기"), 오른쪽에 "노래가 나오고 있어요. 바로 무대를 열까요?", "무대 열기", Gleo 힌트가 보였습니다.
- 무대가 열리고 결과 카드까지 뜬 화면(`song scored 0 (BASIC) over 11 s`): 오른쪽 칸에 상태 줄, 결과 카드, 안내 문구, 미터, 버튼 셋, 두 줄 무음 안내가 모두 스크롤 없이 들어갔습니다. 실내 그림은 미니 플레이어만큼 작아졌습니다.

## 대결 점수판 확인 (2026-10-27)

대결 카드에 좌우 점수판을 넣고([10](10-ui-ux-plan.md) "4번 PR 구현"), 효과음을 끄고(`--es effects off`) 개발용 표시를 숨긴 채(`--es demo on`) duel-two-songs를 `scripts/replay.sh`로 재생하며 무대가 열리고 1초 뒤 Gleo `START_BATTLE`을 보냈습니다. 로그 줄을 기다렸다가 화면을 찍었습니다.

| 시점(로그) | 화면 |
|---|---|
| `duel turn: turn DRIVER` 2초 뒤 | "운전석 차례예요"(파랑), 운전석 칸이 파랑 테두리로 빛나고 "부르는 중", 조수석 칸 "다음 차례" |
| `duel turn: turn PASSENGER, scores {DRIVER=6}` 5초 뒤 | "조수석 차례예요"(빨강), 운전석 칸 "6점", 조수석 칸이 빨강으로 빛나고 "부르는 중" |
| `duel announcing: … {DRIVER=6, PASSENGER=53}` 1.4초 뒤 | "결과를 발표하겠습니다", 운전석 칸이 빛남(첫 차례는 운전석), 조수석 칸 "?" |
| 같은 줄 2.6초 뒤 | 조수석 칸이 빛남, 여전히 "?" |
| `duel crowned`(발표 시작 5.93초 뒤) 0.9초 뒤 | "승자는 조수석!"(빨강), 조수석 칸 "53점"이 빛나며 커짐, 운전석 칸은 어두워짐. 버튼 없음 |
| `duel done`(발표 시작 9.94초 뒤) 0.6초 뒤 | 같은 점수판 아래 "다음 곡에서 역전해 보세요!", "다시 대결", "확인" |

- `duel crowned`는 팡파레가 시작되는 프레임과 같습니다(위 "드럼롤 좌우 번갈이 확인"의 `sound FANFARE` 5.93초). 재생은 `off after 98.0 s … 2 songs ended`로 끝났고 무대를 닫은 뒤 `restored all=true`였습니다.
- 끝난 대결 카드(점수판, 안내, 버튼 둘)가 떠도 오른쪽 칸은 스크롤 없이 들어갔습니다.
- 무승부는 녹음으로 만들기 어려워 단위 테스트(`BoardSideTest`)로만 확인했습니다.

## 실내 그림 확인 (2026-10-27)

실내 시뮬레이터 그림을 새로 그리고([10](10-ui-ux-plan.md) "5번 PR 구현"), 효과음을 끈 채(`--es effects off`) duel-two-songs를 `scripts/replay.sh`로 재생했습니다. 무대가 열리고 1초 뒤 Gleo `START_BATTLE`을 보냈고, 무대가 열린 때부터 `adb shell screenrecord`(2560×1440, 10Mbps, 110초)로 화면을 녹화했습니다. 녹화에서 장면마다 프레임을 뽑아 보았습니다. 리뷰를 반영한 마지막 빌드는 개발용 표시를 숨기고(`--es demo on`) 한 번 더 녹화했습니다.

| 시점(녹화 시작부터) | 실내 그림 |
|---|---|
| 노래 중(10초) | 파랑 앰비언트 줄 셋이 바닥으로 번지고, 실내가 옅게 파랑으로 물듦 |
| 운전석 곡이 끝난 피날레(41초) | 줄이 등급 색(크림색)으로 밝아지며 줄 자체도 흰빛을 띰. 가운데 송풍구 두 곳에서 "환호 바람" 줄기가 대시보드 색 테두리를 두르고 나옴 |
| 발표 중(83~86초) | 독서등 원뿔이 운전석, 조수석으로 번갈아 켜지고 앰비언트도 그쪽 색(파랑, 빨강)으로 바뀜 |
| 승자 공개(89초, `duel crowned` 직후) | 빨강 앰비언트, 조수석 독서등 원뿔, 조수석 방석 위 바람 물결 3줄(좌석 색 테두리 덕에 빛 웅덩이 위에서도 또렷함), 이름은 파랑 "조수석 -2°" |
| 발표가 끝난 뒤(93초) | 통풍 물결과 "-2°"가 사라지고 이름이 원래 색으로 돌아옴 |

- 두 번 모두 `duel announcing` 5.93초 뒤 `duel crowned`, 9.94초 뒤 `duel done`이었고, 재생은 `off after 98.0 s … 2 songs ended`, 무대를 닫은 뒤 `restored all=true`였습니다.
- `screenrecord`로 2560×1440 화면을 110초 녹화한 파일은 약 120MB였습니다. 녹화가 끝나기 전에 `adb pull`로 가져오면 파일이 깨지므로(`moov atom not found`), 녹화 시간이 다 지난 뒤 가져옵니다.
- CPU(위 "상태별 화면 확인"과 같은 방법: car-2 재생, 무대가 열리고 10초 뒤부터 3초 간격 5번 `top -p`, 코어 하나 기준, 재생마다 평균): 이 변경 전의 그림과 번갈아 쟀습니다.
    - 프레임마다 모든 모양을 그린 처음 구현은 36.8%, 38.9%였고, 같은 때 이 PR 전의 그림은 32.0%, 29.6%였습니다(약 7%p 높음). 99번째 백분위 프레임 시간도 29ms, 32ms로 이 PR 전(21ms, 21ms)보다 길었습니다.
    - 바뀌지 않는 부분을 크기마다 한 번 이미지로 그려 두게 바꾼 뒤에는 31.5%, 30.1%였고, 이 PR 전의 그림은 30.9%, 29.9%였습니다.
    - 자기 그래픽 레이어를 두고 앰비언트 빛 마스크를 알파 전용 이미지로 바꾼 마지막 빌드는 네 번 재생해 28.0%, 39.2%, 29.4%, 27.2%(평균 31.0%)였고, 이 PR 전의 그림은 33.8%, 32.5%, 30.4%, 28.0%(평균 31.2%)였습니다. 같은 빌드도 재생마다 10%p 넘게 달라질 때가 있어(39.2%), 둘은 같은 수준으로 봅니다.
    - 끊긴 프레임(`dumpsys gfxinfo`의 Janky frames)은 마지막 빌드가 0.88~1.15%, 이 PR 전의 그림이 1.54~1.83%였습니다. 90번째 백분위는 둘 다 17~21ms, 99번째 백분위는 25~27ms와 23~32ms였습니다.
- 이미지를 만드는 시간: 임시 로그로 재니 그림 크기 596×961px에서 처음 보일 때 16.3ms, Lab에서 돌아올 때 9.7ms였습니다(이미지 네 장과 흐림을 만들던 때). 크기가 바뀔 때마다(미니 플레이어가 나타나거나 사라질 때 등) 이 시간이 한 번 듭니다.
- 메모리: 크기마다 이미지 세 장(차와 바닥, 좌석과 운전대는 ARGB, 앰비언트 빛 마스크는 알파 전용)을 둡니다. 596×961px에서 약 5MB입니다.
- `scripts/regress.sh --install`은 처음에 `night` 하나가 실패했습니다(실패 줄은 남기지 못함). `night`만 다시 돌리면 통과했고(밤 밝기 31 34 33 39 36 23 15 26), 전체를 두 번 더 돌려 두 번 모두 6개가 통과했습니다. `night`는 8번 읽은 밝기 중 20%보다 낮은 값이 있어야 통과하는데, 무대 연출의 밝기가 마이크 소리에 따라 달라 20% 아래가 한두 번뿐일 때가 있습니다. 실내 그림은 차량 값을 바꾸지 않습니다.

## 세로 한 칸 확인 (2026-10-27)

Pleos 홈 화면은 왼쪽에 주행 정보(853px), 오른쪽에 앱 자리(1682×1210, 오른쪽 끝 2535~2560은 손잡이)를 둡니다. 손잡이를 누르기만 하면 바뀌지 않았고, 왼쪽으로 끌면(`adb shell input swipe 2547 717 1700 717 600`) 앱 자리가 둘로 나뉘어 CarStage는 `frame=[853,90,1694,1300]`(841×1210), 오른쪽 칸에는 내비게이션(`[1718,90,2559,1300]`)이 떴습니다. 가운데 손잡이를 다시 오른쪽 끝으로 끌면(`adb shell input swipe 1706 717 2555 717 600`) `[853,90,2535,1300]`으로 돌아왔습니다. 앱을 다시 설치하면 나눈 상태가 풀려 두 칸 너비로 돌아갑니다.

- 바꾸기 전: 한 칸에서도 두 칸 배치를 그대로 좁혀, 실내 그림이 폭 약 330px로 작아지고 오른쪽 칸은 약 370dp였습니다. 대결 카드(폭 560dp)가 칸 폭으로 줄고, 버튼이 좁아지고, 제목과 안내가 여러 줄로 꺾였습니다.
- 바꾼 뒤([10](10-ui-ux-plan.md) "6번 PR 구현"): 무대를 Gleo `OPEN_STAGE`로 열고 `START_BATTLE`로 대결을 시작하며 캡처했습니다.

| 상태 | 한 칸 화면 |
|---|---|
| 무대 닫힘 | 실내 그림이 위에 크게(높이 약 800dp), 그 아래 상태 줄, "음악 앱에서 노래를 틀어 주세요", "노래 시작", Gleo 힌트 |
| 무대 열림 | 그림이 작아지고(약 540dp), 그 아래 상태 줄, 제목, 미터, 버튼 셋, 무음 안내 |
| 대결 차례 | 그림을 접고 대결 카드를 원래 폭(560dp)으로 가운데에, 그 아래 미터와 버튼 둘, 안내 |
| 결과 카드(duel-two-songs 재생, `song scored 6 (BASIC) over 41 s`) | 그림을 접고 상태 줄, 결과 카드, 제목, 미터, 버튼 셋이 스크롤 없이 들어감 |
| 다른 앱의 음악 + 무대 닫힘 | 그림, 미니 플레이어("음악 재생 중", "곡 정보 보기"), "노래가 나오고 있어요. 바로 무대를 열까요?", "무대 열기" |
| 다른 앱의 음악 + 무대 열림 | 그림을 접고 미니 플레이어와 무대 조작을 가운데에 |

- 미니 플레이어는 시험 재생 앱을 무음만 내게 잠시 바꿔 빌드해 띄웠습니다(위 "미니 플레이어를 왼쪽 칸으로 옮긴 확인"과 같은 방법). 확인 뒤 원래 소스로 다시 빌드해 설치했고, 저장소의 `tools/`는 바꾸지 않았습니다.
- 두 칸으로 되돌린 뒤 무대 닫힘과 대결 차례 화면은 바꾸기 전과 같았습니다.

## 마이크 권한 설명 확인 (2026-10-27)

무대가 닫힌 화면에서 마이크가 허용되지 않은 동안 주 버튼 아래에 마이크를 쓰는 이유를 두고([10](10-ui-ux-plan.md) 7장 "첫 실행과 마이크 권한"), `adb shell pm revoke --user 10 com.carstage.app android.permission.RECORD_AUDIO`로 권한을 회수해 확인했습니다(회수하면 앱 프로세스가 끝나므로 다시 시작함).

- 권한이 없을 때: "노래 시작" 아래에 마이크를 쓰는 이유가 Gleo 힌트 대신 보였습니다(확인 때 문구는 "마이크를 들어요"였고, 리뷰 뒤 "노래에 반응하려고 마이크로 소리를 들어요. 소리는 저장하거나 보내지 않아요."로 바꿈).
- "노래 시작"을 누르면 시스템 권한 창("CarStage에서 오디오를 녹음하도록 허용하시겠습니까?")이 화면 아래쪽 절반을 덮었고, 선택지는 "앱 사용 중에만 허용"과 "허용하지 않고 다시 묻지 않음" 둘이었습니다. 한 번 회수한 뒤라 거절이 곧 "다시 묻지 않음"이므로, 그 뒤에는 버튼을 눌러도 창이 뜨지 않고 설명 줄과 기존 안내(`NoticeBoard.MIC_NOT_ALLOWED`, 설정에서 허용하라는 문장)가 남습니다.
- "앱 사용 중에만 허용"을 누르자 무대가 열렸고(`stage open`, `microphone on`), 무대를 닫으니 그 자리가 Gleo 힌트로 돌아왔습니다.
- 권한을 다시 회수하고 앱을 연 뒤 설정 앱을 전체 화면으로 열어 가린 채 `pm grant`로 허용하고 돌아오니, 설명 줄이 Gleo 힌트로 바뀌었습니다(화면이 다시 보일 때 권한을 다시 확인함).
- 리뷰 반영 뒤: 권한 창에서 "허용하지 않고 다시 묻지 않음"을 누르자 설명 줄과 "마이크를 허용해야 노래에 반응할 수 있어요. …" 안내가 함께 보였고, 설정 앱으로 가린 채 `pm grant`로 허용하고 돌아오니 안내가 내려가고 설명 줄이 Gleo 힌트로 바뀌었습니다. 화면을 나눠 옆 칸에서 허용하는 경우(CarStage가 멈추지 않음)를 위해 버튼을 누를 때도 권한을 다시 확인합니다.

## 첫 곡 힌트 확인 (2026-10-27)

무대마다 첫 곡의 점수가 나오기 전까지 제목 아래에 "곡이 끝나면 점수가 나와요"를 두고([10](10-ui-ux-plan.md) 7장), 효과음을 끈 채(`--es effects off`) duel-two-songs를 `scripts/replay.sh`로 재생하며 캡처했습니다.

- 무대가 열리고 8초 뒤: 상태 줄, "두 분이 타셨네요. 노래 대결도 해 보세요", 그 아래 "곡이 끝나면 점수가 나와요", 미터, 버튼 셋이 보였습니다.
- `song scored 6 (BASIC) over 41 s` 2초 뒤: 결과 카드가 뜨고 힌트는 사라졌습니다.
- 재생은 `stage closed, restored all=true`로 끝났습니다. 점수가 나지 않은 짧은 곡, 결과 카드를 닫은 뒤, 같은 무대와 새 무대는 단위 테스트(`SongSessionTest.aStageSaysAScoreWillComeUntilItsFirstSongIsScored`)로 확인했습니다. 대결의 차례 곡은 결과 카드 대신 대결로 점수가 넘어가므로 처음에는 힌트를 끄지 않았고, 대결이 끝나 카드를 닫으면 힌트가 다시 나왔습니다(리뷰에서 찾음). 차례 곡의 점수도 세게 고치고 `aDuelsScoredTurnEndsTheHintThatAScoreWillCome`로 확인했습니다.

## 안내 아이콘과 접기 확인 (2026-10-27)

안내 줄에 종류 아이콘을 붙이고 3초 뒤 접게 한 뒤([10](10-ui-ux-plan.md) 7장 "안내 문구"), 효과음을 끈 채 종류마다 안내를 띄워 캡처했습니다.

| 안내 | 띄운 방법 | 화면 |
|---|---|---|
| 주의 | Gleo `OPEN_STAGE`로 무대를 열면 호스트 마이크가 무음이라 약 3초 뒤 "마이크 소리가 들어오지 않아요. …" | 삼각형 아이콘과 두 줄 글. 나타나고 3초 뒤 한 줄의 작은 글과 "…"로 접힘 |
| 주의 + 정보 | 무대가 열린 채 `--es night on`으로 밤 안내를 덧붙임 | 글이 바뀌어 다시 펼쳐졌다가 3초 뒤 접힘. 아이콘은 줄에서 가장 심각한 주의 |
| 정보 | 무대를 연 채 `am force-stop` 뒤 다시 시작(`restored the values the last stage left`) | 원 안의 i와 "지난 무대에서 바뀐 차량 설정을 원래대로 되돌렸어요." 한 줄이라 접혀도 글 크기만 조금 작아짐 |
| 오류 | 앱을 멈추고 `run-as`로 `files/stage/restore-journal.properties` 자리에 폴더를 만든 뒤 다시 시작(`restore journal unreadable; discarding it`) | 원 안의 !와 "지난 무대 기록을 읽지 못했어요. …" 두 줄. 8초 뒤에도 펼친 채 |

- 접힌 주의 안내를 누르자 다시 두 줄로 펼쳐졌고, 3초 뒤 다시 접혔습니다.
- 오류를 띄운 폴더는 앱이 읽지 못한 기록으로 보고 지웠습니다(`ls files/stage`가 빔). 확인 뒤 밤은 차를 따르게(`--es night car`) 되돌렸습니다.
- 리뷰 반영 뒤 다시 확인: 처음에는 주의도 접어 마이크 무음 안내의 "확인해 주세요"가 "…"에 가려졌습니다. 정보 줄만 접게 바꾼 뒤, 마이크 무음 안내(주의)는 10초 뒤에도 두 줄 그대로였습니다. 지난 무대 복원 안내(정보)는 3초 뒤 한 줄로 접혔고, 누르자 펼쳐져 5초 뒤에도 펼친 채였습니다. 접히는 줄의 누르는 영역은 76px 높이(`uiautomator` 경계 `[1839,813][2383,889]`)였고, 아이콘과 글이 한 덩어리로 왼쪽 정렬되었습니다. 28dp 아이콘을 키워 보니 i와 !의 점과 막대가 떨어져 보였습니다.

## 시험 음악 앱 확인 (2026-10-31)

에뮬레이터에는 YouTube Music과 멜론이 없고 VLC는 휴대폰용 앱이라, 시험 재생 앱(`tools/mediatester`)을 Pleos [Music/Audio 앱 개발 가이드](https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/music-audio-app-developement-guide)의 필수 항목(`MediaBrowserService`, `MediaSession`과 Callback, `android:appCategory="audio"`, `androidx.car.app.launchable`)을 갖춘 음악 앱으로 바꿨습니다. 곡은 `scripts/media-library.sh`로 `build/music`의 파일 10개(Kevin MacLeod 7곡, 애국가 오케스트라 연주, 가수 노래가 있는 가요 한 곡, `car-1`)를 앱 안(`files/music`)에 넣었습니다.

| 확인 | 결과 |
|---|---|
| 차의 미디어 소스 | 재생을 시작하자 `dumpsys car_service --services CarMediaService`가 `For user 10, current playback media component: com.carstage.mediatester/com.carstage.mediatester.LibraryService`로 바뀌었습니다. 차가 "지금 음악을 맡은 앱"으로 MBS 앱을 고릅니다. 사용자 0의 소스는 `-`로 비어 있습니다 |
| Pleos 홈 미디어 위젯 | 시험 앱이 재생 중이고 차의 미디어 소스로 잡혀도 "재생 중인 항목 없음"이었습니다. VLC로 틀 때도 같았습니다. 위젯이 무엇을 보여 주는지는 공식 문서에 없습니다 |
| CarStage 미니 플레이어 | `Big Rock`, `Kevin MacLeod`, `0:03 / 3:50`. 시험 앱이 MediaSession에 넣은 제목, 아티스트, 길이를 그대로 읽었습니다 |
| 미디어 키 | `KEYCODE_MEDIA_NEXT` → 다음 곡(`cipher`), `PAUSE` → `PAUSED`, `PLAY` → 그 자리에서 `PLAYING`, `PREVIOUS`(3초 안) → 앞 곡(`Big Rock`) |

- 공식 가이드는 재생 상태(`PlaybackState`) 값을 따로 정의하지 않고, 차가 MBS로 재생 상태를 확인한다고만 적습니다. 미디어 센터 템플릿을 쓰지 않은 앱은 "미디어 앱 라이브러리에 표시되지 않습니다".
- VLC의 스트림 목록은 예전에 맥에서 띄운 HTTP 링크를 기억합니다. 맥의 파일이 없어지면 누를 때 아무 반응 없이 404로 끝납니다. 곡은 시험 앱에 넣어 쓰는 편이 안정적입니다.
- `am start`에 띄어쓰기가 든 값을 넘길 때는 `adb shell "am start … --es song 'Big Rock'"`처럼 명령 전체를 따옴표로 감쌉니다. 감싸지 않으면 `Big`만 넘어갑니다.
## 상태 아이콘 확인 (2026-10-31)

실내 그림 아래의 글자 값 줄("앰비언트 켜짐 · 밝기 50% · #FFFFFF" 등 5줄)을 글자 없는 아이콘 5개(`home/CabinStatus`)로 바꾸고, Gleo로 무대를 열어 대결을 끝까지 돌리며 0.6초마다 캡처했습니다.

| 순간 | 아이콘 |
|---|---|
| 무대 열림 | 앰비언트 전구가 조명 색(분홍)으로 켜지고 밝기 링이 채워짐, 테두리가 같은 색. 나머지 넷은 회색 |
| 곡 끝(환호 바람) | 풍량 날개가 하늘색으로 바뀌며 한 단만큼 돌고, 아래 점 4개 중 1개가 켜짐 |
| 대결 발표 | 독서등 두 개 중 운전석, 조수석이 번갈아 따뜻한 색으로 켜지고 빛 원뿔이 밝아짐 |
| 승자 조수석 | 조수석 통풍 물결 3단이 파랗게, 조수석 온도계가 파랗게 바뀌고 ▼ 표시 |
| 발표 끝 | 독서등·통풍·온도·풍량이 회색으로 돌아감 |

- 꺼진 독서등은 처음에 타일 위쪽의 작은 반원뿐이라 보이지 않았습니다. 등 윤곽과 줄, 흐린 빛 원뿔을 늘 그리게 바꿨습니다.

## 목소리 모드 확인 (2026-10-31)

조명 색으로 목소리 모드(자홍~분홍)와 노래 모드(청록~시안)를 나눴습니다([09](09-voice-reaction-plan.md) 5장 "목소리 모드와 노래 모드"). `off after` 줄 끝에 `modes <목소리 모드 프레임> voice / <노래 모드 프레임> music (<전환 횟수> switches)`를 더했습니다.

녹음 재생(`scripts/replay.sh`, 파일을 마이크 대신 넣음)

| 녹음 | 목소리 모드 | 노래 모드 | 전환 |
|---|---|---|---|
| car-1 (84.0초) | 1142 프레임 (58%) | 816 | 36번 |
| car-2 (30.9초) | 413 (58%) | 299 | 4번 |

- 같은 파일을 JVM에서 돌린 오프라인 계산과 프레임 수까지 같습니다
- 따라 부른 구간의 정답이 없어 58%가 맞는지는 모릅니다

맥 스피커 → 맥 마이크(시험 음악 앱 `com.carstage.mediatester`로 재생, 아무도 부르지 않음, 무대를 약 35~40초 열고 `--es stage close`로 닫음)

| 재생한 곡 | 입력 소스 | 목소리 모드 | 그 밖의 값 |
|---|---|---|---|
| Big Rock (보컬 없음) | `MIC` | 0 / 773 프레임 (0%) | 노래 1, 큰 소리 80, 최대 -30.6 dBFS, 반주 -62.2 dBFS |
| Big Rock | `VOICE_RECOGNITION` | 0 / 863 (0%) | 노래 3, 큰 소리 71, 최대 -32.1, 반주 -60.9 |
| Laser Groove (보컬 없음) | `MIC` | 0 / 861 (0%) | 노래 5, 큰 소리 123, 최대 -44.8, 반주 -63.6 |
| Laser Groove | `VOICE_RECOGNITION` | 0 / 852 (0%) | 노래 10, 큰 소리 193, 최대 -37.8, 반주 -60.6 |
| 가요 한 곡 (가수 노래 있음) | `MIC` | 28 / 936 (3%), 2번 전환 | 노래 83, 최대 -26.2, 반주 -55.6 |
| car-1 (따라 부른 녹음) | `MIC` | 116 / 963 (12%), 8번 전환 | 노래 93, 큰 소리 315, 환호 1 |

- 목표: 음악만 나올 때 목소리 모드 10% 미만 → 보컬 없는 두 곡 모두 0%(두 소스 모두)
- 따라 부른 녹음은 스피커를 거쳐도 목소리 모드로 들어갔습니다. 다만 파일 재생(58%)보다 훨씬 낮습니다
    - 스피커 소리가 작아(반주 약 -61 dBFS) 대부분의 프레임이 음높이를 보는 바닥(-50 dBFS) 아래였습니다. 실차의 마이크 크기에서 다시 잽니다
- 가수의 노래가 있는 곡은 아무도 부르지 않아도 목소리 모드가 됩니다(가요 한 곡 3%). 소리만으로는 가를 수 없고 차의 에코 제거가 필요합니다
- `MIC`와 `VOICE_RECOGNITION`의 차이: 판정은 둘 다 0%이고, 크기 차이는 1~7 dB로 재생마다 다른 정도입니다. 에뮬레이터는 두 소스 모두 에코 제거가 없으므로(위 "오디오") 근거가 되지 않습니다 → 기본은 `MIC`로 둡니다
    - 바꾸는 곳: `MicInput.SOURCE`(`audio/AudioInput.kt`) 하나. 개발 빌드는 `--es micsource <이름>`으로 다음 무대부터 바꿉니다(앱이 끝날 때까지)
    - 마이크 시작 로그에 `source=<요청한 소스>`를 적고, 플랫폼이 다른 소스로 열면 `(the platform uses …)`를 덧붙입니다
- 에뮬레이터의 재생음은 기기 안에서는 마이크로 돌아오지 않지만(위 "오디오"), 맥 스피커에서 나온 소리는 맥 마이크로 들어옵니다. 음악만 시험은 이 경로를 씁니다

화면

| 스크린샷 | 모드 | 화면 글로우(오른쪽 가장자리) | 실내 그림 앰비언트 줄 |
|---|---|---|---|
| `music-mode-bigrock.png` (Big Rock, 22초) | 노래 | `(17,68,79)` 청록 | `(89,213,221)` 청록 |
| `voice-mode-car-1.png` (car-1, 11초) | 목소리 | `(84,34,84)` 자홍 | `(231,90,175)` 분홍 |
| `back-to-music-car-1.png` (4초 뒤) | 노래로 돌아옴 | `(16,66,72)` 청록 | `(84,214,208)` 청록 |

- 상태 아이콘 줄의 앰비언트 아이콘, 목소리 반응 미터도 같은 색을 따랐습니다
- 스크린샷은 레포에 넣지 않았습니다

회귀 시험과 그 밖

- `scripts/check.sh`, `scripts/check.sh --device`, `scripts/regress.sh --install` 통과(6개 시나리오, `night`의 밤 밝기 8%: 노래 모드의 바닥 20% × 0.4)
- 시험 중 에뮬레이터가 adb에 잡히지 않았습니다. qemu 프로세스는 있었지만 `detected a hanging thread 'QEMU2 main loop'`를 남기고 5554/5555 포트를 열지 않았습니다 → qemu를 끄고 `scripts/run-emulator.sh`로 다시 켜서 이어 갔습니다

## 노래 목록 확인 (2026-10-31)

차의 음악 앱 MBS에 `MediaBrowser`로 붙는 노래 목록(`media/SongShelf`, [07](07-convenience-features.md) "노래 목록")을 에뮬레이터에서 확인했습니다. 스크린샷은 `build/shots/`(레포에 넣지 않음)에 있습니다.

- 차에서 보이는 MBS 앱(`queryIntentServices`, 매니페스트 `<queries>`): 4개 → `com.android.bluetooth`(블루투스, `avrcpcontroller.BluetoothMediaBrowserService`), `com.android.music`(Android Open Source Music Player), `com.carstage.mediatester`(Media Tester), `org.videolan.vlc`(VLC, `PlaybackService`)

| 확인 | 결과 |
|---|---|
| 시험 음악 앱 재생 → CarStage 앞으로 | `arrived: pausing com.carstage.mediatester (stage CLOSED, offer null, command false …)` → `music playing=false`. 목록 `root has 11 items (0 folders) of 11`, 첫 줄 "부를 노래를 골라 주세요" |
| "Vibe Ace" 누름 | `songs: play "Vibe Ace" (file:vibe-ace.ogg)` → 시험 앱 `playing Vibe Ace` → 약 3~8초 뒤 `stage open` → `microphone on`. 미니 플레이어 `Vibe Ace`, `Kevin MacLeod`, `0:00 / 1:01`. 목록의 Vibe Ace 줄이 강조 바탕과 초록 막대 아이콘 |
| 열린 무대의 목록 버튼 | 미니 플레이어 아래에 목록이 열리고, 실내 그림 대신 상태 아이콘 줄만 남음. 무대 닫기 → `stage closed, restored all=true` |
| 재생 중인 앱 없음, 기억한 앱 없음 | `songs: no source to follow; 4 to pick from` → "음악 앱" 카드에 4개 → Media Tester 누름 → 11곡 |
| VLC | 거절 없이 연결. root `//org.videolan.vlc/r` → 폴더 4개(홈, 내 라이브러리, 스트림, 재생 목록). 내 라이브러리 → 폴더 4개(아티스트, 앨범, 장르, 트랙). 스트림 → 곡 9개(예전에 맥에서 띄운 HTTP 링크, 제목 없이 URL인 것도 있음). 연결만으로 VLC가 세션을 만들어 미니 플레이어가 VLC를 따라감("제목 없는 곡") |
| 블루투스 앱 | 연결됨, root `__ROOT__` → `onError`(`failed to list __ROOT__`). 연결된 폰이 없어서로 보임. 카드 "곡 목록을 불러오지 못했어요"와 "다시 불러오기", 안내 줄 오류 |
| 세로 한 칸(음악 재생 중 칸 나누기) | 액티비티가 다시 만들어져도 곡을 멈추지 않음(`arrived` 로그 없음, 곡 계속 재생). 실내 그림이 접히고 목록이 칸 안에 들어감 |
| 음악 재생 중 Gleo `OPEN_STAGE` | 곡을 멈추지 않고 `stage open`, `gleo OPEN_STAGE -> SUCCESS` |
| `scripts/regress.sh --install` | 6개 모두 PASS(gleo, drive, recovery, probes, night, replay) |

- Pleos 홈은 앱을 한 칸에 계속 띄워 두므로, 이미 보이는 CarStage에 `am start`를 보내면 `onStart` 없이 새 intent만 옵니다. 그래서 명령이 아닌 새 intent도 "앞으로 나옴"으로 셉니다.
- 열린 무대에서는 글로우가 계속 움직여 `uiautomator dump`가 실패합니다. 시험 스크립트는 좌표로 눌렀습니다.
- 실차에서 확인할 것: 멜론·YouTube Music이 `onGetRoot`에서 CarStage를 허용하는지(허용 목록 방식이면 거절 안내만 나옴), 폰이 연결된 블루투스 앱이 무엇을 주는지, 같은 곡을 다시 골랐을 때 처음부터 트는지(앱마다 다름).

## Pleos TTS로 MC 음성 확인 (2026-10-31)

10/25에는 Pleos TTS SDK가 "Coming Soon"이라 Android 표준 `TextToSpeech`를 썼고, 에뮬레이터의 음성 엔진을 찾지 못해 말하지 않았습니다(위 "MC 음성 확인"). 10/31에 Pleos 저장소(`nexus-playground.pleos.ai`)를 보니 `ai.pleos.playground:TextToSpeech`가 2.1.2부터 3.3.1까지, `SpeechToText`도 3.3.1까지 올라와 있었고, 공식 문서도 3.3.1 기준이었습니다. 문서에 따르면 `ON_DEVICE` 모드는 네트워크와 앱 등록 없이 동작하고, 지원 언어에 `ko-KR`이 있습니다(`ON_DEVICE` 모드에도 적용되는지는 문서에 없음). 그래서 `Announcer`를 이 SDK의 `ON_DEVICE` 모드로 바꾸고 Lab `--es probe voice`로 확인했습니다.

| 확인 | 결과 |
|---|---|
| 권한(`pleos.car.permission.TTS_SERVICE`) | 설치 때 허용되었습니다(`granted=true`). SDK가 음성 서비스를 찾는 `<queries>`(`ai.pleos.playground.caas.intent.action.SPEECH_START`)를 함께 넣어 주므로, 앱의 `TTS_SERVICE` 조회는 지웠습니다 |
| 초기화 | 진단이 엔진을 시작하자(`prepare`) `initialize`가 약 0.5초 뒤 `onSuccess`로 끝났습니다. 에뮬레이터의 음성 서비스는 4.2.10이고, SDK는 4.1.29 이상을 요구합니다(앱 매니페스트에 합쳐지는 `ai.pleos.playground.caas.min_version_name`) |
| 세 줄 말하기 | `speak` 뒤 2~38ms에 `onStart`, 1.8~2.7초 뒤 `onDone`이 왔습니다("결과를 발표하겠습니다" 2.4초, "승자는 조수석! 53점입니다" 2.7초, "무승부! 환상의 케미예요" 1.8초). `onReady`는 `speak`마다 바로 왔습니다. 맥 스피커로 한국어 소리가 났습니다(사용자 확인) |
| 소리를 낸 곳(`dumpsys audio`) | 앱이 아니라 음성 서비스(`ai.pleos.playground.caas`, 사용자 10)가 줄마다 `AudioTrack`을 만들었고, 용도는 `USAGE_MEDIA`, 내용은 `CONTENT_TYPE_SPEECH`, 모노 22,050Hz였습니다. 서비스는 오디오 포커스를 따로 요청하지 않았고, 앱의 일시 포커스(`USAGE_ASSISTANCE_SONIFICATION`, 덕킹 허용)는 줄마다 요청하고 반납했습니다 |
| 용도 넘기기(`setAudioAttributes`) | 리뷰에서 `USAGE_MEDIA`로 말하면 음악 감지(`MusicPlayback`)가 MC의 말을 다른 앱의 음악으로 읽는다는 지적이 나와, 엔진에 앱의 용도(`USAGE_ASSISTANCE_SONIFICATION`, `CONTENT_TYPE_SPEECH`)를 넘겼습니다. 그 뒤 서비스의 `AudioTrack`도 이 용도로 바뀌었고, 세 줄을 말하는 동안 `music playing=true` 로그가 없었습니다. 음악 감지는 이와 별도로 말소리 재생을 세지 않게 했습니다 |
| 말하는 중 포커스 잃기 | 첫 줄을 말하는 1.4초에 시험 음악 앱이 포커스(`GAIN`)를 가져가자 `voice focus lost (-1)`로 앱이 `stop()`을 보냈고, 1ms 뒤 `onStop`이 왔습니다. 서비스의 재생은 약 0.17초 뒤 소리를 줄이고(`volumeShaper`) 0.33초 뒤 멈췄습니다. 다음 줄은 그대로 말했습니다 |
| 음성 서비스를 끈 경우 | `pm disable-user --user 10 ai.pleos.playground.caas`로 서비스를 끄고 진단을 돌리자 `initialize`가 `onFailure(UNKNOWN)`로 끝났고, 앱은 `voice: the speech service did not start (UNKNOWN); the screen's words stand alone`을 경고로 남긴 뒤 세 줄을 모두 `voice skipped`로 건너뛰었습니다. 앱 프로세스는 그대로였습니다. 서비스를 다시 켜자(`pm enable`) 다음 진단에서 정상으로 말했습니다 |

- MC의 말은 다른 앱의 음악과 함께 섞여 나옵니다. 실차에서 음악을 줄이는지(덕킹)는 실차의 오디오 정책을 따릅니다.
- SDK의 이벤트는 어느 문장인지 알려 주지 않습니다. 앱은 한 번에 한 줄만 말하므로 지금 줄의 이벤트로 보고, 앱이 보낸 `stop()`의 `onStop`은 세어서 버립니다(앱이 이미 그 줄을 끝냈으므로).
- 실차에 같은 음성 서비스가 있는지는 아직 모릅니다. 시작하지 못하면 지금처럼 로그만 남기고 화면 문구로 대신합니다.

## 실내 그림의 미리보기 표시 확인 (2026-10-31)

앱이 켜진 뒤 차가 제어를 거절한 적이 있으면 실내 그림 위에 "미리보기 · 차가 허용하면 실내가 이렇게 반응해요"를 두게 하고([11](11-no-control-plan.md) 5장), 2026-10-24의 방법대로 제어 권한 세 개를 잠시 뺀 개발 빌드와 원래 빌드를 번갈아 설치해 확인했습니다. 효과음은 끄고(`--es effects off`) Gleo로 무대를 열었습니다.

| 확인 | 결과 |
|---|---|
| 권한을 뺀 빌드, 무대를 열기 전 | 표시가 없었습니다(아직 거절된 적이 없음) |
| 권한을 뺀 빌드, Gleo로 무대 열기 6초 | 앰비언트 스위치, 밝기, 색이 `SecurityException: … need Permission`으로 거절되었고(`the car does not let this app control it`), 그림 위에 표시가 나왔습니다. 안내 줄의 "차가 허용하지 않은 기능은 화면에서만 반응해요."도 함께 나왔습니다 |
| 같은 빌드, 무대 닫기 | `stage closed, restored all=true`. 안내는 내려가고, 그림 위의 표시는 남았습니다(이 차는 제어를 허용하지 않는다고 보고, 다음 무대가 다시 시도하는 동안 깜빡이지 않게) |
| 같은 빌드, 세로 한 칸(841dp) | 표시가 그림 위 한 줄에 들어갔고, 버튼과 안내 줄의 자리는 그대로였습니다. 개발 빌드의 "개발자 Lab" 버튼 바로 왼쪽에 붙지만 겹치지 않았습니다. 리뷰를 반영해 표시를 그림과 따로 재고 그 높이를 그림의 최소 높이(360dp)에 더하게 바꾼 뒤에도, 무대를 연 채와 닫은 뒤 모두 표시와 그림이 함께 나왔습니다 |
| 권한이 있는 빌드, 무대 열기와 닫기 | 거절 로그(`rejected`)가 0줄이었고, 표시는 나오지 않았습니다 |
| 권한이 있는 빌드, `scripts/regress.sh --install` | 여섯 시나리오가 모두 PASS였고, 리뷰를 반영한 빌드로 다시 돌려도 모두 PASS였습니다. 거절 로그(`does not let this app`)는 0줄이었습니다 |

- 권한을 뺀 빌드는 `assembleDebug`로만 만들고 매니페스트를 바로 되돌렸습니다(lint는 `MissingPermission`으로 실패함).

## 음악 앱 정보로 곡 끝 확인 (2026-10-31)

음악 앱이 다른 곡으로 넘어가는 순간 부르던 곡을 끝내고(`TrackEnds`), 결과 카드에 곡 제목을 붙이게 했습니다(`SongTitles`). 알림 접근이 없어도 노래 목록이 연결한 앱의 세션을 미니 플레이어가 따라가게 했습니다(`NowPlaying.shelfSession`). 맥 스피커로 소리가 나지 않게, ffmpeg로 소리 없는 30초 곡 두 개("Quiet Song A", "Quiet Song B", AAC)를 만들어 시험 음악 앱에 넣었습니다(`scripts/media-library.sh`). CarStage에는 알림 접근을 주지 않았습니다.

| 확인 | 결과 |
|---|---|
| 노래 목록에서 Media Tester를 고름 | `songs: connected to com.carstage.mediatester` → `now playing: the song list's session com.carstage.mediatester` → `now playing: following com.carstage.mediatester`. 알림 접근 없이 세션을 따라갔습니다 |
| 아무것도 틀지 않은 그 세션 | 처음 빌드는 미니 플레이어에 "제목 없는 곡 0:00"을 띄웠습니다. 노래 목록의 세션은 그 앱이 재생할 때까지 숨기게 고쳤습니다(리뷰에서 앱이 목록 연결에 맞춰 지난 곡을 세션에 되살려 둘 수 있다는 지적이 나와, 제목이 있어도 재생 전에는 숨기고 버튼은 미디어 키를 보냄). 고친 빌드에서는 시험 앱을 끈 뒤 CarStage를 다시 열어도 카드가 나오지 않았습니다 |
| 목록에서 Quiet Song A를 누름 | 처음 한 번은 `songs: play "Quiet Song A"`와 함께 무대가 열렸지만, 그 뒤로는 재생만 되고 무대가 열리지 않았습니다. 시험은 곡을 누른 직후 Gleo `OPEN_STAGE`로 무대를 열었습니다. 2026-10-31 정정: 무대가 열리지 않은 것이 아니라, 곡을 누르면 음악 캡처의 화면 녹화 동의 창("CarStage으로 녹화 또는 전송을 시작하시겠습니까?")이 떠서 그 창을 누를 때까지 무대가 열리기를 기다린 것입니다(이 창은 `uiautomator dump`가 다른 창으로 잡아 처음에는 보지 못함). "시작하기"를 누르자 무대가 열렸습니다 |
| 시험 앱이 A를 마치고 B를 틂(30초 뒤) | `song changed in com.carstage.mediatester: "Quiet Song B"` → 3ms 뒤 `song ended as the music app moved on` → `song scored 0 (BASIC) over 30 s: …, title "Quiet Song A"`. 결과 카드 머리에 "Quiet Song A · 흥 점수", 미니 플레이어에 "Quiet Song B 0:02 / 0:30"과 일시정지 버튼이 보였습니다(소리 없는 곡이라 0점) |
| 리뷰를 반영한 빌드로 다시 | `songs: play "Quiet Song A"` → Gleo로 무대 열기 → 30초 뒤 `song changed in com.carstage.mediatester: "Quiet Song B"` → 39ms 뒤 `song ended as the music app moved on` → `song scored 0 (BASIC) over 29 s: …, title "Quiet Song A"`. 시험 앱을 끈 뒤 CarStage를 다시 열어도 미니 플레이어가 나오지 않았습니다(재생 전의 노래 목록 세션은 숨김). `scripts/regress.sh` 6개 모두 PASS |

- 시험 음악 앱의 화면(`TesterActivity`)으로 곡을 틀면 CarStage가 가려져 무대가 닫힙니다. 무대를 연 채 시험하려면 CarStage의 노래 목록에서 곡을 누릅니다. 재생 서비스(`LibraryService`)는 내보내지 않아서 `am start-foreground-service`로 부를 수 없습니다.
- 에뮬레이터에 예전 시험 앱이 깔려 있으면 노래 목록의 앱 목록에 Media Tester가 나오지 않습니다. 새로 빌드해 설치하고(`scripts/gradle.sh :mediatester:assembleDebug`), CarStage를 다시 시작해야 목록이 다시 찾습니다.

## 차를 아는 MC 확인 (2026-11-01)

무대 인사, 곡 점수, 대결 차례, 충전 완료를 말하는 MC([12](12-mc-plan.md), `StageMc`)를 넣고, 효과음을 켠 채(맥 스피커로 나옴) 시험 음악 앱의 소리 없는 곡으로 확인했습니다. 무대는 노래 목록에서 곡을 누르고 화면 녹화 동의 창의 "시작하기"를 눌러 열었습니다.

| 확인 | 결과 |
|---|---|
| 무대 인사 | `stage open` 1.2초 뒤 `mc: "두 분이 타셨네요. 오늘은 노래 대결 어때요?"`. 에뮬레이터는 앞좌석 두 자리가 모두 찬 것으로 읽혀(위 "착석 감지 확인") 착석 인사가 나왔습니다. 음성 서비스가 이미 준비되어 3ms 만에 말하기 시작해 3.1초 동안 말했습니다 |
| 곡 점수, 처음 빌드 | 곡이 바뀐 순간 `song scored 1 (BASIC) … title "Quiet Song A"` 1ms 뒤에 `mc: "Quiet Song A, 1점! 좋은 출발이에요. 다음 곡은 더 크게 불러 봐요."`를 말해, 바로 이어 나온 박수와 겹쳤습니다. 점수가 박수보다 조금 먼저 나오기 때문입니다 |
| 곡 점수, 고친 빌드 | `song scored 2 (BASIC)` → 8ms 뒤 `sound SHORT_APPLAUSE playing for 1800 ms` → 점수 2.5초 뒤(박수 1.8초와 그 뒤 0.5초가 끝난 뒤) `mc: "Quiet Song A, 2점! …"`, 4.5초 동안 말함 |
| 회귀 시험 | `scripts/regress.sh` 6개 모두 PASS(효과음과 목소리는 꺼진 채, 예상 길이만큼 환호 판정은 버림) |

- 리뷰를 반영해 말을 대기열로 한 줄씩 내보내고, 말하기 직전에 그 말이 아직 맞는지 보게 했습니다. 대결 시작과 무대 인사가 겹치는 경우, 화면에서 바뀐 점수, 다시 연 무대 같은 순서와 경우는 단위 시험(`StageMcTest`)에서 시각까지 확인합니다.

## 앱 아이콘 확인 (2026-11-01)

Android 기본 아이콘(`sym_def_app_icon`)을 직접 그린 적응형 아이콘으로 바꿨습니다([10](10-ui-ux-plan.md) 9장 3번).

| 확인 | 결과 |
|---|---|
| Pleos 앱 목록(독의 격자 버튼) | 다른 앱처럼 둥근 사각형으로 잘려, 자홍·보라 바탕에 흰 마이크와 소리 물결로 보였습니다 |
| 독의 최근 앱 칸 | 설치 직후에는 실행 중이던 CarStage 칸에 예전 기본 아이콘이 남아 있었습니다(앱을 다시 시작해도 같음). 다른 앱(Connect Mirroring)을 연 뒤에는 최근 앱 칸에 새 아이콘이 나왔습니다 |
| 리소스 폴더 | minSdk 28이라 적응형 아이콘은 `mipmap-anydpi`에 둡니다(`-v26`을 붙이면 lint `ObsoleteSdkInt`). 처음에 폴더를 옮긴 직후 리소스 링크가 `resource mipmap/ic_launcher not found`로 실패했는데, 리뷰에서 깨끗한 복사본으로 다시 빌드하자 성공해서 낡은 증분 빌드 탓으로 봅니다 |

## 노래·목소리 조명과 에코 제거 끝에서 끝까지 확인 (2026-11-01)

맥 스피커를 쓰지 않고(소리 없는 에뮬레이터, `HEADLESS=1 scripts/run-emulator.sh --silent`), 시험 음악 앱이 Big Rock을 틀면 `scripts/virtual-cabin.sh`가 그 소리를 80 ms 늦게 반사시켜 마이크에 넣고, 맥 TTS 목소리(`virtual-cabin.sh voice`)를 섞어 확인했습니다. 기준 신호는 개발용 캡처 도우미(`scripts/playback-tap.sh start`)로 받았습니다. 무대는 Gleo로 열고 약 30초 뒤 닫았습니다(`microphone off after …` 줄).

| 경우 | 목소리 모드 | 노래 모드 | 에코 제거 |
|---|---|---|---|
| 음악만, 끔 | 0 | 743 | - |
| 음악만, 켬 | 0 | 830 | 지연 435 ms를 찾음, 1.7 dB 뺌 |
| 음악 + 목소리(+10 dB), 끔 | 339 | 477 | - |
| 음악 + 목소리(+10 dB), 켬 | 219 | 589 | 0.2 dB |

- 노래만 나올 때는 끝까지 노래 모드(청록), 목소리가 섞이면 목소리 모드(자홍)로 바뀌고 끝나면 돌아옵니다. 0.5초 간격 캡처에서 실내 조명, 화면 글로우, 상태 알약, 목소리 막대가 함께 바뀌었습니다.
- 에코 제거는 이 시험 환경에서 효과를 보이지 못했습니다. 엔진 단위 테스트(합성 장면: 20 dB 이상, 3초 안 수렴, 목소리 손실 3 dB 이하)는 통과합니다. 원인은 시험 경로입니다.
    - 반사음이 기준 신호보다 515~555 ms 늦습니다(Lab `--es probe echo`, 41초에 +40 ms). 엔진은 실차 기준으로 0~500 ms만 찾습니다. 개발용 `--ei echo_hold_ms 400`으로 기준을 늦추면 지연은 찾지만(198~448 ms) 계속 움직입니다(재정렬 12~24번).
    - 에뮬레이터 출력 스트림(`streamAudio`)은 맥 시계로 초당 약 48,064샘플이고 시험 도구는 48,000샘플씩 넣어, 반사음이 1초에 약 1.3 ms씩 기준에서 어긋납니다. 받은 샘플 수만큼 넣게 바꾸면 지연은 잡혔지만 목소리 판정이 망가져 되돌렸습니다.
    - 그래서 에코 제거를 켜면 오히려 목소리 모드 프레임이 줄었습니다(339 → 219). 실차(스피커와 마이크가 같은 시계, 수십 ms)에서 다시 잽니다(아래 "남은 검증").
- 결정: 에코 제거는 기본으로 끕니다(`PlaybackTap.cancels`, 개발 빌드 `--es echo on`으로 켬). 출시 빌드는 캡처 동의를 묻지 않고 캡처 서비스와 권한도 넣지 않습니다(개발 빌드 매니페스트에만). 동의 창이 무대마다 화면 녹화 문구로 뜨고 포그라운드 서비스 알림이 필요한데, 알림은 Pleos 허용 전까지 쓰지 않는 원칙이고 효과도 아직 보이지 못했기 때문입니다. 동의를 받은 뒤 무대 열기가 거절되면 캡처를 바로 놓습니다.
- 위 결정 뒤 같은 에뮬레이터에서 다시 확인: `scripts/regress.sh --install` 6개 모두 통과(`recovery` 18초), 음악만 `0 voice / 816 music`, 음악 + 목소리 `361 voice / 467 music`.
- 이 확인에서 고친 것
    - 캡처 도우미가 CarStage 소리를 빼는 규칙을 `addMixRule`에 제외 값으로 넣어 거절당했습니다(`IllegalArgumentException: Illegal rule value 32772`). `AudioPlaybackCaptureConfiguration.excludeUid`처럼 `excludeMixRule(RULE_MATCH_UID, uid)`로 바꿨습니다. 실패 로그는 리플렉션 예외 대신 원인 예외를 남깁니다.
    - 시험 도구가 20 ms마다 쌓인 만큼만 넣고 모자라면 무음으로 채워, 반사음이 수시로 잘렸습니다(블록의 약 40%). 60 ms 지터 버퍼로 바꾼 뒤 1초 구간마다 원곡과의 상관이 0.78~0.96이 되었습니다(이전에는 0.2 안팎).
    - 무대 경로에서 기준 신호 재정렬이 35초에 35~74번 일어났습니다(Lab 진단 경로는 0번). 마이크 스레드가 분석·연출도 해서 프레임을 몰아 읽기 때문이라, 재정렬 기준을 40 ms에서 250 ms로 넓혔습니다(0~3번으로 줄어듦).
    - Lab 에코 진단이 메인 스레드에서 도우미 소켓을 열다 막혔습니다(`NetworkOnMainThreadException`). 지연 탐색은 3초까지로 넓혔습니다(2 kHz).
    - 마이크 종료 줄에 엔진 상태(지연을 찾았는지, 신뢰도, 재정렬 수)를 더했습니다.
- 소리 없는 에뮬레이터를 창 없이(`-no-window -gpu swiftshader_indirect`) 켜면 Pleos 주행 화면 앱(`ai.umos.drivingview`)이 몇 초마다 `SIGSEGV`로 죽고 다시 떠서, 차량 SDK 호출이 5초씩 시간 초과되고 무대가 열리지 않았습니다(`GEAR_UNKNOWN`). 시험 에뮬레이터에서만 `adb shell pm disable-user --user 0 ai.umos.drivingview`, `--user 10`으로 끄면 크래시가 멈춥니다(되돌리기는 `pm enable`). 창이 있는 모드는 맥 화면이 꺼져 있거나 잠겨 있으면 Metal 초기화 뒤 멈춰 adb에 잡히지 않아, `run-emulator.sh`에 `HEADLESS=1`을 더했습니다.
- 회귀 시험: 창 없는 에뮬레이터에서 `recovery`가 실패했습니다(복원이 20초 기준을 넘어 22초에 끝남). 앱을 막 설치한 뒤 차량 SDK의 기능 조회가 5초씩 시간 초과되기 때문이고, 이 변경 이전 빌드도 같은 에뮬레이터에서 2번 중 2번 실패해 이번 변경과 무관합니다. 앱이 데워진 뒤 `gleo`, `drive`, `probes`, `night`, `replay`는 통과했습니다.

## MC 끄기 확인 (2026-11-01)

첫 화면 상태 줄의 MC 스위치([12](12-mc-plan.md) 4장)를 시험 음악 앱의 소리 없는 곡으로 확인했습니다. 무대는 Gleo 흉내(`OPEN_STAGE`)로 열고 곡은 `FINISH_SONG`으로 끝냈습니다. 말하는 도중에 끄는 경우만 효과음과 목소리를 켰고(맥 스피커로 나옴), 나머지는 `--es effects off`로 소리 없이 로그로 봤습니다.

| 확인 | 결과 |
|---|---|
| 스위치 | 두 칸 화면에서 `CarStage` 옆에 182×76dp 한 칸으로 놓이고, 접근성 트리에서 스위치(`checkable`, `checked`)로 잡힙니다. 누르면 `voice turned off` |
| 끈 채로 무대 열기, 곡 끝내기 | `stage open` 2ms 뒤 `mc: left out, the MC is off`, 12초 곡의 점수(`song scored 6 (BASIC) … title "Quiet Song A"`) 뒤에도 `mc: left out, the MC is off`. `voice ready`가 없어 음성 엔진을 시작하지 않았습니다 |
| 무대 중에 켜기 | `voice turned on` → `voice ready: Pleos on-device speech` → 다음 곡의 점수 `mc: "8점! 좋은 출발이에요. …"`(소리를 끈 시험이라 `(muted)`) |
| 말하는 도중에 끄기 | Lab 음성 진단의 첫 줄 "결과를 발표하겠습니다"가 548ms째일 때 `--es mc off` → 1ms 뒤 `voice stopped after 548 ms`. 놓은 엔진에서 늦게 오는 `voice ended`·`voice started`는 없었고, 오디오 포커스 목록도 비었습니다. 다음 두 줄은 `voice off: "…"` |
| 끈 채로 Lab 음성 진단 | 엔진 상태 `[voice] off: the MC was turned off`, 세 줄 모두 `voice off: "…"`. 첫 빌드는 엔진 상태가 `let go as the stage closed`로 나와 꺼진 이유가 보이지 않아서 고쳤습니다 |
| 다시 설치하고 시작 | 스위치가 꺼진 채로 나오고(`checked="false"`), 시작할 때 `voice turned off` |
| 세로 한 칸 | 무대가 열리고 충전 중(80%)일 때 `CarStage`, `듣고 있어요`, `충전 중 · 80%`, 스위치가 한 줄(약 710dp, 쓸 수 있는 폭 777dp)에 들어갑니다. 무대가 열리면 상태 줄이 위로 옮겨 가므로 adb로 누를 때는 위치를 다시 읽어야 합니다 |
| 회귀 시험 | `scripts/regress.sh --install` 7개 모두 통과(새 `mc` 시나리오 4초) |

- 상태 줄은 넘치면 다음 줄로 넘기게(`FlowRow`) 했지만, 넘치는 모습은 에뮬레이터에서 보지 못했습니다. `settings put system font_scale 1.3`(사용자 10)을 넣어도 화면 구성의 글자 배율이 바뀌지 않았습니다. 확인 뒤 설정은 지웠습니다.

## 기본 흐름 확인 (2026-11-01)

기본 흐름([13](13-basic-flow.md))을 시험 음악 앱으로 화면을 눌러 확인했습니다. 개발 빌드라 무대를 열 때 재생 캡처 동의 창이 나와서 "취소"를 눌렀습니다(출시 빌드는 묻지 않습니다).

| 확인 | 결과 |
|---|---|
| 음악이 나오는 중에 앱 열기 | 음악이 멈추지 않고(`arrived` 줄 없음, 세션 `PLAYING`), "지금 나오는 곡으로 부를까요?", `Big Rock`, "이 곡으로 시작", 목록 맨 위에 재생 중 표시가 나옵니다 |
| 이 곡으로 시작 | `stage open, driving [vehicle, simulator]`. 화면은 곡 제목, "곡이 끝나면 점수가 나와요", 목소리 막대, "그만하기", "다음 곡"만 보이고 미니 플레이어가 없습니다 |
| 다음 곡 | `now playing: next com.carstage.mediatester` → `song changed … "cipher"` → `song scored 18 (BASIC) over 28 s … title "Big Rock"`. 화면에 "cipher", "방금 곡 18점 · 좋은 출발" |
| 그만하기 | `closing stage: closed from the screen` → `stage closed, restored all=true` → `basic flow: pausing the music with the stage` → 세션 `PAUSED`. 화면에 "오늘의 무대 · 1곡", "최고 18점", `Big Rock` |
| 주행으로 닫힘 → 이어서 부르기 | 기어 D: `closing stage: safety: NOT_PARKED` → `basic flow: pausing the music with the stage` → 세션 `PAUSED`. 기어 P: "다시 정차했어요. 이어서 부를까요?" → 누르면 `stage open` 뒤 `basic flow: playing the music again` → 세션 `PLAYING`(멈춘 위치 16.8초부터) |
| 구석 스위치 | "모든 기능"을 누르면 예전 첫 화면("노래 시작" 버튼)으로, "기본 화면으로"를 누르면 다시 기본 흐름으로 바뀝니다 |
| 회귀 시험 | `scripts/regress.sh --install` 7개 모두 통과(시작할 때 `--es features all`) |

## 목소리·노래 미터 확인 (2026-11-01)

- 같은 날 한 줄로 되돌렸습니다. 실차에서 노래와 목소리를 나눌 수 있을지 아직 모르므로, 이름표 없이 둘 중 큰 값을 조명 색으로 보여 줍니다([13](13-basic-flow.md)). 반주 크기 값(`AudioEvent.Frame.music`)은 그대로 둡니다.

무대 미터를 "목소리"와 "노래" 두 줄로 나눴습니다. "노래"는 마이크에서 반주로 추정한 부분의 크기(-60 dBFS를 0, -25 dBFS를 가득)이고, 음악 앱의 음량을 읽은 값은 아닙니다. 차 스피커의 음악과 목소리는 마이크에 섞여 들어오므로 둘 다 추정입니다.

| 확인 | 결과 |
|---|---|
| 차 안 녹음 재생(`--es replay car-2.wav`, 말소리 + 음악) | 2초 간격 캡처 세 장에서 목소리 줄 약 0.7~0.85, 노래 줄 약 0.4~0.6으로 따로 움직임. 목소리 줄은 자홍, 노래 줄은 청록 |
| 단위 시험 | 반주만 있는 프레임(`music = 0.8`)에서 노래 줄은 0.8로 차고 목소리 줄은 비어 있음, NaN은 0으로 보고 천천히 내려감 |

## 마이크 입력 종류 비교 (2026-11-01)

핸즈프리 통화와 Gleo도 차에서 나오는 소리를 마이크에서 빼야 하므로, 차의 오디오 장치가 통화·음성용 입력(`VOICE_COMMUNICATION`, `VOICE_RECOGNITION`)에 에코 제거를 걸어 둘 수 있습니다. 그렇다면 무대는 그 입력으로 듣기만 하면 음악이 빠진 마이크 소리를 받습니다. 이를 재는 Lab 진단을 만들었습니다(`MicSourceProbe`).

- 실행: 다른 앱으로 곡을 틀고 무대를 닫은 채 `adb shell am start -n com.carstage.app/.MainActivity --es probe micsources --ei mic_seconds 8 --ei mic_rounds 2`. 입력 다섯 가지(`MIC`, `VOICE_RECOGNITION`, `VOICE_COMMUNICATION`, `UNPROCESSED`, `VOICE_PERFORMANCE`)를 차례로 녹음하고, 순서를 라운드만큼 되풀이해 곡의 큰 부분이 한 입력에 몰리지 않게 합니다. 무대와 같은 분석을 거쳐 입력마다 입력 크기, 반주 크기, 목소리·노래 값, 목소리 모드 비율, 녹음에 걸린 에코 제거(`AEC on/off/n/a`)를 남깁니다. 다른 앱이 마이크를 잡아 0만 들어오거나(디지털 무음) 녹음이 도중에 멈춘 녹음은 줄에 표시하고 요약 평균에서 뺍니다(음악을 뺀 것으로 잘못 읽지 않게)
- 읽는 법: 아무도 부르지 않을 때 어떤 입력만 입력 크기와 반주 크기가 크게 낮고 목소리 모드가 0%라면 그 입력이 음악을 뺀 것입니다. 이어서 따라 부르며 한 번 더 돌려 그 입력에서 목소리가 남는지(목소리 모드 비율) 확인합니다
- 에뮬레이터 결과(호스트 마이크, 시험 음악 앱 `Big Rock`, 6초 × 2라운드): 앱이 쓸 수 있는 에코 제거가 없고(`AEC available to apps: false`), 다섯 입력 모두 입력 약 -51~-53 dBFS, 반주 약 -61~-64 dBFS, 목소리 모드 0%로 차이가 1~2 dB 안입니다. 맥 스피커 소리가 맥 마이크에 작게 들어온 정도라 판단 근거가 되지 않습니다. 실차에서 다시 잽니다(아래 "남은 검증")

## 무대 화면 정리 (2026-11-01)

- 무대 중 안내 줄의 "차에서 나오는 음악을 빼지 못해서, 노래 속 목소리에도 조명이 반응할 수 있어요."를 뺐습니다. 지금은 모든 무대가 음악을 빼지 않고 듣고, 사용자가 할 수 있는 일이 없는 안내라서입니다(`EchoOff.notice`). 캡처가 도중에 멈춘 경우의 안내(개발 빌드만)는 남깁니다. 에뮬레이터에서 무대를 열고 화면 트리에 그 글이 없음(0건)을 확인했습니다.
- MC 켜고 끄기를 상태 줄 끝에서 오른쪽 아래 구석의 말풍선 아이콘으로 옮겼습니다([12](12-mc-plan.md) 4장). 누르면 `voice turned off`, 아이콘이 회색에 빗금으로 바뀝니다.

## 기본·대결 모드 확인 (2026-11-01)

처음 화면의 [기본 | 대결]과 대결 마무리를 시험 음악 앱(`Big Rock`)으로 화면을 눌러 확인했습니다([13](13-basic-flow.md)).

| 확인 | 결과 |
|---|---|
| 대결을 고르고 "이 곡으로 시작" | `stage open` → `duel requested` → `duel turn: turn DRIVER`, 화면에 "운전석 차례예요" |
| 플레이어 다음 곡 두 번(차례마다 10초 넘게) | `duel turn: turn PASSENGER, scores {DRIVER=27}` → `duel announcing` → `duel crowned` → `duel done` |
| 대결 끝 | `duel done` 2초 뒤 `closing stage: the duel is over` → `pausing the music with the stage` → 세션 `PAUSED`. 닫힌 화면에 결과 카드("승자는 조수석!", 27·63점, "확인")만 보임 |
| 확인 | 결과 카드가 사라지고 [기본 | 대결], "부를 노래를 골라 주세요", 노래 목록 |
| 앱 강제 종료 뒤 다시 시작 | 대결이 고른 채로 남고 결과 카드는 없음 |
| 플레이어 일시정지 | `closing stage: closed from the screen` → `now playing: pause` (약 0.03초, 4초를 기다리지 않음) |
| 회귀 시험 | `scripts/regress.sh --install` 7개 모두 통과 |

## 주행 기록(Odometer) 확인 (2026-11-01)

MC의 주행 기록 인사([12](12-mc-plan.md) 5장)를 만들며 Vehicle SDK의 Odometer를 확인했습니다. 함수 이름은 API 레퍼런스의 목록에서 가져왔습니다. 함수 페이지는 헤드리스 Chrome으로 그려도 제목 말고는 본문이 비어 있었고(EvBattery 페이지도 같음), 공개 샘플(`Pleos-Playground`)에도 Odometer 예제가 없습니다. 그래서 타입은 Gradle 빌드 오류로 확인했습니다.

| 확인 | 결과 |
|---|---|
| 타입(빌드 오류) | 시간(`getTripCurrentDrivingTime`, `getTripCumulativeDrivingTime`, `getTripSinceRefillDrivingTime`)은 `Int?`, 거리(`getTripCurrentDrivingDistance`, `getTripCumulativeDrivingDistance`, `getTripSinceRefillDrivingDistance`, `getCurrentDistanceDriven`)는 `Float?`이고, `check…Capabilities`는 콜백 하나로 `CapabilityConfig`를 돌려줍니다(앱은 null도 받게 `CapabilityConfig?`로 받음) |
| Virtual Console | `set /vehicle/odometer usage_time_min=95 traveled_distance_km=123.4`가 받아들여집니다. 콘솔은 이 값을 차량 속성 `PERF_ODOMETER`, `TRIP_DRIVING_DISTANCE`, `TRIP_DRIVING_TIME`(마지막 둘은 vendor 속성)으로 내보낸다고 표시합니다. 두 값은 줄일 수 없습니다(되돌리면 400 `traveled_distance_must_not_decrease`, `usage_time_must_not_decrease`). 그래서 이 에뮬레이터의 콘솔 값은 95분, 123.4km로 남아 있습니다 |
| Lab 차량 진단(`--es probe vehicle`의 `odometer`) | 주행 기록 여섯 개(현재·누적·주유 후의 시간과 거리)는 capability 조회와 읽기 모두 `ERROR_NOT_SUPPORTED_PROPERTY`. `getCurrentDistanceDriven`만 `access=READ`, 값 123.4(콘솔의 `traveled_distance_km`)였습니다. 앱이 선언한 권한(그중 주행 관련은 `CAR_DRIVE`) 그대로 읽혔고, 주행 기록에 어느 권한이 필요한지는 에뮬레이터가 지원하지 않아 확인하지 못했습니다 |
| 제품(`TripReader`) | 첫 무대에서 `trip: driving time not supported, distance not supported`, 무대마다 `trip: the car reports none`(첫 무대는 capability 조회 두 번에 약 0.5초, 다음 무대부터는 미지원을 기억해 SDK를 부르지 않음(4ms)). 인사는 평소 문장("두 분이 타셨네요. 오늘은 노래 대결 어때요?")이었습니다 |
| 시험 명령 | `--es trip 95` 뒤 `--es stage open`으로 연 무대의 인사는 "1시간 반 넘게 달려 오셨어요. 한 곡 부르며 기지개도 켜 봐요."였습니다. 회귀 시험 `trip` 통과(7초) |

- 에뮬레이터 v2.1.0은 주행 기록을 지원하지 않으므로, 값의 단위(시간은 분, 거리는 km로 가정)와 "이번 주행"이 언제 처음부터 세는지는 실차에서 확인해야 합니다. 앱은 12시간 이하이고 평균 속도가 3km/h 초과 200km/h 이하일 때만 시간을 믿습니다.

## 대결 차례마다 곡 고르기 확인 (2026-11-01)

| 확인 | 결과 |
|---|---|
| 운전석 차례 끝(플레이어 다음 곡) | `duel turn: turn PASSENGER` → `duel: pausing the music for the passenger to pick a song` → 왼쪽에 노래 목록(맨 위부터), 오른쪽에 "조수석, 부를 노래를 골라 주세요" |
| 14초 뒤 목록에서 AcidJazz | `songs: play "AcidJazz"` → `duel: the passenger's song starts`. 14초 부르고 다음 곡 → `duel announcing … PASSENGER=12` (고르는 동안은 세지 않음) |
| 발표 | `duel: pausing the music for the announcement` → `duel done` → 2초 뒤 `closing stage: the duel is over` |

- 처음 만든 것은 곡을 고르는 순간 조수석 차례가 끝났습니다. 고르는 시간이 조수석의 곡으로 셈해졌고, 일시정지를 보낸 직후 세션이 "재생 중"을 한 번 더 알려 와서 "멈춘 곡을 이어 부름"으로 읽었기 때문입니다. 멈춘 것을 본 뒤의 재생만 이어 부름으로 보게 고쳤습니다.
- 차례가 바뀌는 순간은 음악 앱이 다음 곡을 막 시작하는 때라 재생 중이 아닐 수 있어, 3초 안에 재생이 시작되면 그때 멈춥니다(`DUEL_PAUSE_WAIT_MS`).
- 2026-11-02 전체 흐름 시험: 30초 곡의 0:29에 "곡 끝내기"(Gleo `FINISH_SONG`)로 운전석 차례를 끝내자, 조수석 곡 고르기를 위해 보낸 일시정지가 곡 끝 3초 안이라 `TrackEnds`가 "끝까지 틀고 멈춤"으로 읽었습니다(`song changed in … "Quiet Song A"`). 그때는 이것을 고른 것으로 보아 `duel: the passenger's song starts`가 곡을 고르기 전에 나왔고, 고르는 동안의 무음까지 조수석 곡으로 셌습니다. 이제 조수석 곡을 기다리는 동안에는 음악이 재생될 때만 고른 것으로 봅니다(`PassengerPick`). 같은 상황(0:28)을 다시 만들자 `duel: "Quiet Song A" is not playing; the passenger's turn starts when music plays`가 남고 차례는 시작되지 않았으며, 목록에서 "Quiet Song B"를 누르자 `duel: the passenger's song starts`가 나왔습니다. 고른 곡이 불러오는 동안(일시정지, 버퍼링)도 같은 줄이 남고, 차례는 그 곡이 재생될 때 시작합니다.

## Gleo 응답 모드 확인 (2026-11-01)

Gleo 응답에 `mode`를 넣고([12](12-mc-plan.md) 6장) 회귀 시험 `gleo`로 확인했습니다. Gleo 요청은 `am start`로 흉내 냈습니다.

| 확인 | 결과 |
|---|---|
| `OPEN_STAGE`(새 무대, MC 켬) | `gleo OPEN_STAGE -> SUCCESS (beep)` 뒤 MC 인사 `mc: "…"`. `mc: left out, Gleo said it`은 없었습니다 |
| `START_BATTLE`(바로 시작) | `duel turn: turn DRIVER`, `gleo START_BATTLE -> SUCCESS (beep)` 뒤 `mc: "대결을 시작합니다! 운전석부터 한 곡 불러 주세요."` |
| `FINISH_SONG` | `gleo FINISH_SONG -> SUCCESS (voice)` |
| 대결 모드에서 화면 버튼처럼 연 무대(`trip` 시나리오) | 인사가 대결을 권하지 않고 "무대 준비 끝! 마음껏 불러 주세요."였습니다. 앞 무대에서 기다리던 운전석 차례 안내는 다음 무대에서 `mc: left out, its stage closed`로 빠졌습니다. 고치기 전에는 이 안내가 다음 무대의 인사보다 먼저 나왔습니다 |
| 회귀 시험 | `gleo`, `mc`, `trip` 통과 |

- 실제 Gleo가 `beep`을 받아 효과음만 내는지는 듣지 못했습니다. 에뮬레이터의 Gleo는 대화 중이 아니면 앱의 응답을 버립니다(위 "흥 점수와 피날레 확인"의 `Reject AppActionResponse … status: Sleeping`).

## 차량 앱 품질 점검 (2026-11-01)

Pleos 앱 심사 가이드, 디자인 가이드와 Android 차량 앱 품질 기준(Google 공식 `android/skills`의 주차 중 앱 스킬)으로 점검한 항목을 고치고 확인했습니다.

| 확인 | 결과 |
|---|---|
| 무대를 여는 중 표시 | `--es stage open` 직후 연속 캡처에서 "무대를 여는 중입니다" 왼쪽에 진행 표시가 돌았습니다(약 1초, 이 확인 뒤 색을 노래 목록의 진행 표시와 같은 회색으로 바꿈. 자홍색은 배경 대비 3.97:1이라 4.5:1 기준에 못 미침). Pleos 심사 가이드는 버튼 동작이 2초 안에 끝나지 않으면 처리 중임을 보이라고 합니다. 닫기는 에뮬레이터에서 15ms 만에 끝나 캡처되지 않았습니다 |
| 글자 24 이상 | 실내 그림의 좌석 이름("운전석", "조수석")과 작은 플레이어의 시간("0:05", "0:30")을 24sp로 올렸고, 둘 다 자리 안에 들어갑니다(시험 음악 앱의 소리 없는 곡 "Quiet Song A"로 확인) |
| 뒤로 가기 | 노래 목록의 "다른 음악 앱 고르기"를 누른 뒤 `adb shell input keyevent KEYCODE_BACK`을 보내자 앱을 나가지 않고 노래 목록으로 돌아왔습니다(CarStage가 계속 앞에 있음) |
| 14.6인치 높이 | `adb shell wm density 200`이면 앱 자리가 1346×968dp가 되어 14.6인치 가로(1360×948)와 비슷합니다. 대기 화면, 무대 중(기본 모드의 한 줄 점수 포함), 대결 카드와 "그만하기"가 모두 스크롤 없이 들어갔습니다 |
| 853dp 높이 | `wm density 227`이면 1185×853dp가 되어 폭이 두 칸 기준(1168dp와 여백)보다 좁아지므로 한 칸 배치가 됩니다. 실내 그림이 접히고 대결 카드, 미터, "그만하기", 플레이어가 들어갔지만 아래 여유가 거의 없습니다. 800~810dp(Connect-W, WF)에서는 플레이어가 스크롤 아래로 갈 수 있습니다 |
| 회귀 시험 | `scripts/regress.sh --install` 8개 모두 통과 |

- `adb shell wm size`로 화면 높이만 줄이면 Pleos가 앱 창을 1682×1210 그대로 두고 아래를 하단 바로 가리므로 시험에 쓸 수 없습니다. 시험 뒤 `wm density reset`, `wm size reset`으로 되돌렸습니다.
- 12.9인치 가로(1274×855, 두 칸)는 밀도만으로는 흉내 낼 수 없습니다. 정확히 보려면 FormFactorConsole을 씁니다([10](10-ui-ux-plan.md) 2장 "화면 크기").

## 백그라운드 복귀와 창 크기 변경 확인 (2026-11-01)

Pleos 앱 심사 가이드의 두 기준을 확인했습니다. 하나는 백그라운드에서 다시 연 앱이 마지막 상태를 되살리는 것("Functionality and Performance")이고, 다른 하나는 해상도가 바뀌어도 상태를 잃지 않는 것("Display Compatibility")입니다.

| 확인 | 결과 |
|---|---|
| 무대를 연 채 설정 앱 열기 | 설정 앱이 앞에 오자 `closing stage: app moved to the background` → `stage closed, restored all=true`(9ms) |
| 10초 뒤 CarStage로 돌아오기 | `offering to sing on after the background`, 화면 "다시 오셨네요. 이어서 부를까요?", "이어서 부르기", "괜찮아요" |
| "이어서 부르기" | `stage open`, 무대가 닫힐 때 멈춘 시험 곡(Test Song)이 다시 재생됨. 맥 스피커로 소리가 나서 약 20초 뒤 멈췄습니다 |
| 무대를 연 채 창 나누기(`input swipe 2547 717 1700 717`) | CarStage 창이 841dp 폭이 되어 한 칸 배치로 바뀌고 무대는 열린 채였습니다. 이벤트 로그에서 다른 칸의 설정 앱은 `wm_relaunch_resume_activity`로 다시 만들어졌고, CarStage는 다시 만들어지지 않았습니다 |
| 밀도 바꾸기(`wm density 200`) | 주행 화면 앱만 다시 만들어지고 CarStage는 0번 |

- 예전에는 창 크기나 밀도가 바뀌면 액티비티를 다시 만들었습니다(매니페스트에 `configChanges`가 없었음). 무대 자체는 `AppGraph`에 있어 이어졌지만 화면의 기억(펼친 안내, 목록 위치, Lab 화면)은 처음으로 돌아갔습니다. 이제도 두 칸과 한 칸 사이를 넘나들면 오른쪽 조작 부분은 새로 그려집니다.
- 액티비티를 다시 만들지 않게 되자, 실내 그림의 좌석 이름이 밀도가 바뀐 뒤에도 예전 크기로 남는 문제가 드러나 고쳤습니다(리뷰에서 찾음).
- 돌아왔을 때 묻는 기한은 3시간입니다. Pleos는 "몇 시간" 쓰지 않았으면 처음 상태여도 된다고 하므로 그보다 짧게 끊지 않았습니다. 통화 중에 돌아오면 통화가 끝난 뒤 묻고, 통화나 주행으로 생긴 질문이 있으면 그것을 그대로 둡니다.
- 회귀 시험 `background`가 떠남과 돌아옴을 확인합니다.
- 앱 프로세스가 백그라운드에서 끝나면 떠난 시각도 사라져 묻지 않습니다. 몇 시간 쓰지 않으면 처음 상태여도 된다는 기준 안이지만, 짧게 떠났어도 메모리가 부족해 프로세스가 끝나면 처음 상태로 시작합니다.

## 터치 피드백 확인 (2026-11-01)

Pleos 디자인 가이드("Introduction")의 "터치하면 진동이나 소리로 반응"을 넣었습니다(`ui/TapFeedback`: `View.playSoundEffect(CLICK)`와 `performHapticFeedback(VIRTUAL_KEY)`). 둘 다 시스템 설정을 따르고, 에뮬레이터의 터치 소리 설정(`settings get --user 10 system sound_effects_enabled`)은 1이었습니다.

| 확인 | 결과 |
|---|---|
| 시스템 클릭 소리 | `dumpsys audio`의 효과음 목록에 `Effect_Tick.ogg`(`/product/media/audio/ui`, 길이 0.094초)가 로드되어 있습니다. 시스템의 `SoundPool`(`USAGE_ASSISTANCE_SONIFICATION`)이 내고 오디오 포커스를 요청하지 않으므로 음악을 멈추거나 줄이지 않습니다 |
| [기본 \| 대결] 선택을 누름 | 누를 때마다 오디오 서버의 재생 시작(`logFgsApiBegin`)이 1번씩 남았습니다. 재생 기록은 약 0.6초 뒤 끝나지만 소리 자체는 0.094초입니다 |
| 실내 그림(누를 수 없는 곳)을 누름 | 재생 시작 0번 |
| 회귀 시험 | `scripts/regress.sh --install` 9개 모두 통과(#86의 `background` 포함) |

- 소리는 맥 스피커로 나오지만 귀로 듣지는 않았습니다. 에뮬레이터 화면은 진동이 없어 진동은 확인하지 못했습니다.
- 무대 중에 누르면 클릭 소리가 마이크에 들어갈 수 있지만, 0.094초는 많아야 3프레임이고 환호는 7프레임(약 0.3초), 목소리 모드는 음높이 있는 6프레임이 이어져야 하므로 클릭은 둘 다 되지 않습니다. 곡 사이의 조용한 틈에 누르면 곡 종료 감지의 조용한 시간이 처음부터 다시 셉니다(틈의 말소리와 같은 알려진 한계).

## 출시 빌드의 차량 제어 끔 확인 (2026-11-02)

Pleos 문서의 "Declaring an unapproved permission or calling the corresponding API causes the app to fail app review."에 맞춰, 출시 빌드를 차량 제어를 끈 판으로 만드는 빌드 스위치(`releaseDrivesCabin = false`)를 넣고 확인했습니다.

| 확인 | 결과 |
|---|---|
| 권한(`aapt2 dump permissions`) | 출시 APK: `CAR_CLIMATE`, `CAR_SEATS`, `CAR_DRIVE`, `CAR_ENERGY`, `CAR_WINDOWS`, `TTS_SERVICE`만 있고 `CONTROL_CAR_*` 세 개와 `CAR_LIGHTS`는 없음. 개발 APK는 그대로 모두 있음 |
| `lintRelease` | 문제 0건 |
| 출시 APK의 코드(`dexdump`) | 차량 출력 `VehicleCabin`이 없습니다(`src/cabinControl`에 있어 개발 빌드에만 들어감). 조명·공조·시트 쓰기 호출은 Pleos SDK 자신의 인터페이스 클래스(`ai.pleos.playground.vehicle.api.HVAC`, `Light`, `Seat`)에만 46건 남고, 우리 코드(`com.carstage`)에는 0건입니다. SDK는 공조(성에 제거)와 좌석(착석) 읽기에 쓰므로 라이브러리 클래스가 함께 들어옵니다. 개발 APK는 4,201건(`VehicleCabin`과 Lab 포함) |
| 출시 빌드로 무대 열기(노래 목록의 소리 없는 곡 "Quiet Song A") | `stage open, driving [simulator]`. 차량 쓰기(`setPropertyAsyncValue` 등)와 `need Permission` 로그 0줄. "차에 연결하지 못해서…" 안내 없음. 실내 그림 위에 처음부터 "미리보기 · 차가 허용하면 실내가 이렇게 반응해요"가 있고, 그림의 조명은 노래 모드 청록으로 움직임 |
| 기본 흐름에서 음악 없이 Gleo `OPEN_STAGE` | `stage refused: opened by voice with no music in the basic flow`(기본 흐름의 규칙대로) |
| `VehicleCabin`을 `src/cabinControl`로 옮긴 뒤 다시 실행 | 소리 없는 곡으로 연 무대: `stage open, driving [simulator]`, 경고 없음. [대결]을 고르고 Gleo `OPEN_STAGE`: `stage open, driving [simulator]`, `gleo OPEN_STAGE -> SUCCESS (voice)`(MC를 꺼 두어 Gleo가 읽음), 화면에 "차에 연결하지 못해서" 안내 없음. 두 경우 모두 차량 쓰기 로그 0줄. "그만하기"로 닫으면 `stage closed, restored all=true` |

- 출시 빌드는 효과음과 MC를 끌 수 없어, 설치 전에 개발 빌드에서 `--es mc off`로 MC를 꺼 두고 확인 뒤 다시 켰습니다.
- 확인 뒤 개발 빌드를 다시 설치했습니다. 출시 빌드는 차량에 쓴 것이 없어 되돌릴 기록도 없었습니다.

## 흥 점수 v2 녹음 재생 확인 (2026-11-02)

곡 자신의 크기를 기준선으로 쓰는 흥 점수 v2([09](09-voice-reaction-plan.md) 5장 "흥 점수 v2")를 개발 빌드로 설치하고, 앱 캐시의 녹음을 `--es replay`로 재생하다가 끝나기 직전에 Gleo `FINISH_SONG`으로 곡을 끝냈습니다(효과음 끔). 기기는 초당 23.44프레임이라, 초당 23프레임으로 센 JVM 시험(`ScoreClipsTest`)과 초의 경계가 조금 다릅니다.

| 녹음 | 내용 | 끝낸 시점 | `song scored` |
|---|---|---|---|
| `car-1.wav` | 차에서 음악을 따라 부름(언제 불렀는지 기록 없음) | 75초 | 47점(BASIC): 참여 30, 열기 7, 호응 0, 완창 10. JVM에서 파일 전체(83.6초)는 50점, v1은 72점 |
| `rock-big.wav` | 록 연주곡, 아무도 안 부름 | 57초 | 0점 |
| `calm-gymnopedie.wav` | 피아노 독주, 아무도 안 부름 | 57초 | 40점(BASIC): 참여 30, 완창 10. 반주가 거의 없어 선율이 쉬는 구간보다 10 dB 넘게 튀어나옴 |
| `lively-laser.wav` | 전자음악, 아무도 안 부름 | 57초 | 48점(BASIC): 참여 21, 열기 25, 완창 2. 레이저 같은 효과음이 피크(곡의 가운데 프레임보다 15 dB 큰 프레임)로 잡힘 |

- 아무도 안 부른 연주곡은 모두 "기본" 등급이지만, 전자음악은 50점 경계에 가깝습니다. 효과음이 날카로운 곡은 피크와 환호로 잘못 읽힐 수 있다는 기존 한계(환호 판정의 "전자음악의 효과음")가 열기에도 그대로 있습니다.
- 확인 뒤 효과음, MC, 기본 흐름을 원래대로 돌렸습니다.

## 팀 키로 서명한 release APK 확인 (2026-11-10)

제출용 release APK를 팀 서명 키(`~/.android/carstage-release.jks`, 팀 비밀번호 관리자에 둔 서명 키)로 서명하는 `scripts/release-apk.sh`를 만들고 확인했습니다.

| 확인 | 결과 |
|---|---|
| `scripts/release-apk.sh` | 비밀번호 관리자에서 받은 비밀번호로 `--no-daemon` Gradle 빌드 한 번(빌드와 함께 끝나는 일회용 데몬, 약 30초)에 `app-release.apk`를 만들었습니다. `apksigner`의 인증서 SHA-256이 팀 인증서와 같고, `aapt2 dump permissions`에 `CONTROL_CAR_*`와 `CAR_LIGHTS`가 없었습니다 |
| 비밀번호 환경 변수 없이 `assembleRelease` | 이전처럼 `app-release-unsigned.apk`(서명 없음)가 나왔습니다. `scripts/check.sh`도 통과했습니다 |
| 비밀번호는 있고 키 파일이 없음 | 설정 단계에서 `CARSTAGE_RELEASE_STORE_PASSWORD is set but the release keystore is not at …`으로 멈췄습니다 |
| 개발 빌드를 지우고 서명한 APK를 `adb install -g` | `Success`, `pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]`(디버그 가능 아님), 제어 권한 0개였습니다. 첫 화면이 열리고 실내 그림 위에 미리보기 줄이 보였으며, 크래시는 없었습니다 |
| 개발 빌드로 되돌리기 | 위 행에서 개발 빌드를 지우기 전에 `run-as … tar`로 `cache/lab`, `shared_prefs`, `files`를 백업해 두었습니다(release 빌드는 디버그 가능 앱이 아니라 `run-as`가 안 됨). 서명이 달라 release 앱을 지우고 개발 빌드를 다시 설치한 뒤 `adb push`와 `run-as … tar xf`로 되돌렸습니다(녹음 20개, md5 일치). `scripts/regress.sh gleo` PASS |

## 음악 앱 없이 Gleo 명령으로 시험 (2026-11-10)

README의 "음악 앱 없이 바로 보기" 순서를 확인했습니다. 시험 음악 앱을 멈춘 채(재생 없음) 기어 P, 첫 화면 [대결], 개발 빌드에 효과음만 끄고(`--es effects off`) Gleo 인텐트를 보냈습니다. 마이크는 `scripts/run-emulator.sh`의 호스트 마이크(방 소리)입니다.

| 보낸 것 | 로그 |
|---|---|
| `OPEN_STAGE` | `stage open`, `gleo OPEN_STAGE -> SUCCESS (beep)`, MC `"두 분이 타셨네요. 오늘은 노래 대결 어때요?"` |
| 13초 뒤 `FINISH_SONG` | `song scored 63 (GOOD) over 12 s`, MC `"63점! 흥이 올랐어요! 목소리가 차 안을 가득 채웠어요."` |
| `START_BATTLE` | `duel turn: turn DRIVER`, MC `"대결을 시작합니다! 운전석부터 한 곡 불러 주세요."` |
| 13초 뒤 `FINISH_SONG` | `duel turn: turn PASSENGER, scores {DRIVER=65}`, MC `"이번엔 조수석 차례예요. 실력을 보여 주세요!"` |
| 13초 뒤 `FINISH_SONG` | `duel announcing … winner DRIVER` → `duel crowned` → `duel done` → `closing stage: the duel is over` |

- 음악이 없으면 조수석 차례에 노래 목록에서 곡을 고르지 않아도, `FINISH_SONG`으로 조수석 차례가 끝나고 점수(63점)가 매겨진 뒤 발표가 이어졌습니다.
- 확인 뒤 에뮬레이터를 바로 껐습니다.

## 공식 문서와 대조한 리스크

- Pleos Playground 문서 기준으로 신청 없이 쓸 수 있는 SDK는 Vehicle State(읽기)와 NaviHelper입니다. **Vehicle Control, TTS, STT, FusedLocation은 "Coming Soon"**으로 아직 신청할 수 없습니다. TTS는 2026-10-31에 `ON_DEVICE` 모드로 신청 없이 썼습니다(위 "Pleos TTS로 MC 음성 확인"). 출시 앱에 신청이 필요한지는 아직 모릅니다. 에뮬레이터에서는 제어 권한이 열려 있지만, 실차 배포 시점에 제어 SDK가 공개될지는 아직 모릅니다.
- Maven에는 `TextToSpeech 3.3.1`, `SpeechToText 3.3.1`, `LLM 2.1.3.2` 아티팩트가 있습니다. 에뮬레이터에는 `pleos.car.permission.TTS_SERVICE`, `STT_SERVICE`, `LLM_SERVICE` 권한이 정의되어 있습니다.

## 남은 검증

- [ ] 실차에서 `--es probe micsources`로 마이크 입력 종류별 음악 제거 비교(음악만, 따라 부르기 두 번). 통화·음성용 입력에서 음악이 빠지면 무대의 기본 입력을 그것으로 바꿉니다(`--es micsource`로 먼저 시험)
- [ ] 실차에서 Odometer 주행 기록의 지원 여부와 필요한 권한, 단위(분, km), "이번 주행"을 처음부터 세는 때(시동, 문 열림), 정차·충전 중에도 시간이 늘어나는지, 첫 읽기가 인사 전(무대가 열린 뒤 1.2초)에 끝나는지. 에뮬레이터 v2.1.0은 주행 기록 여섯 개를 모두 지원하지 않습니다(위 "주행 기록(Odometer) 확인")
- [ ] 실차에서 다른 앱 재생 소리 캡처(`MediaProjection` 동의 창, 음악 앱의 캡처 허용)와 에코 제거 효과(마이크 종료 줄의 `removed … dB`, `locked at … ms`). 에뮬레이터는 시험 경로의 지연(약 0.5초)과 시계 차이(0.13%) 때문에 판단할 수 없었습니다(2026-11-01)

- [x] 사람 목소리로 음높이·박수 감지 확인 (2026-10-23, 휴대폰 녹음 재생. 박수를 18번 잘못 세서 박수 감지를 환호 호응으로 바꿈, [09](09-voice-reaction-plan.md))
- [ ] 사람 목소리와 실제 음악으로 시뮬레이터 연출 확인 (맥 스피커를 켜거나 실차에서)
- [ ] Pleos CPU 기준(25%)이 코어 하나 기준인지 확인하고, 실차에서 연출 중 CPU 사용률 측정
- [ ] Virtual Console에서 공조 쓰기가 보이는지 확인 (앰비언트 색·밝기와 독서등 쓰기는 2026-10-23에 콘솔에 보였습니다)
- [ ] TTS SDK로 MC 멘트 재생 (서버 모드는 클라이언트 ID·시크릿 필요). 2026-10-25: Android 표준 `TextToSpeech`는 에뮬레이터의 Pleos 엔진에 연결되지 않음(위 "MC 음성 확인"). 2026-10-31: Pleos TextToSpeech SDK `ON_DEVICE` 모드로 에뮬레이터에서 말함(위 "Pleos TTS로 MC 음성 확인"). 실차에 같은 음성 서비스가 있는지, 출시 앱에 신청이 필요한지 남음
- [ ] 다른 앱의 재생 정보(트랙 변경) 접근 가능 여부
- [x] 실행 제안과 편의 기능에 필요한 것: 다른 앱의 미디어 재생 상태, Gleo App Schema 등록, 충전 상태, 통화 모드 (2026-10-22, 위 "플랫폼 신호 확인")
- [x] 사람 목소리로 Gleo에게 "노래 무대 열어줘"라고 말했을 때 `OPEN_STAGE`가 호출되는지 (2026-10-22, 호출됨)
- [x] 마이크 분석(음높이, 박수)의 CPU 사용률이 정차 중 기준(25% 이하)을 지키는지 (2026-10-22, 최악의 경우 코어 하나의 1.4%)
- [ ] 실차에서 마이크 분석 CPU 사용률
- [x] 주행이 시작되어 무대가 닫힐 때 독서등 복원이 50001로 실패하는 원인과 대응 (2026-10-23, 위 "주행 중 복원 확인"). 기어 D에서는 독서등 쓰기가 막혀 있습니다. 거절되면 값을 다시 읽어 원래 값이면 복원된 것으로 보고, 아니면 정차하면 되돌립니다
- [ ] 실차에서 주행 중에 막히는 쓰기(독서등 포함)와 그 오류 코드
- [ ] 대결 발표 드럼롤의 좌우 이동과 빨라지기를 귀로 확인 (에뮬레이터는 발표 직전에만 효과음을 켜서 약 7초, 실차에서는 효과음 버스가 스테레오이고 왼쪽 채널이 운전석 쪽인지)
- [ ] 실차에서 에코 제거(`VOICE_RECOGNITION` 입력), 마이크 채널 수, 앰비언트 좌석별 영역 지원 여부 ([09](09-voice-reaction-plan.md) 8장)
- [ ] 실차에서 입력 소스별 목소리 모드 비교 (위 "목소리 모드 확인")
    - 방법: 기어 P, 차 스피커로 보컬 없는 곡을 평소 크기로 틀고 아무도 부르지 않음 → 개발 빌드에서 `--es micsource MIC`로 무대를 40초 열고 닫기 → `--es micsource VOICE_RECOGNITION`으로 같은 곡, 같은 구간 반복 → 이어서 같은 곡에 따라 부르며 두 소스 반복
    - 볼 것: `off after` 줄의 `modes … voice / … music`, `background`(반주 크기), 시작 줄의 `source=`와 `(the platform uses …)`
    - 바꾸는 기준: `VOICE_RECOGNITION`이 음악만일 때 반주가 10 dB 이상 작고 목소리 모드가 낮으면서, 따라 부를 때 목소리 모드 비율이 `MIC`보다 떨어지지 않으면 `MicInput.SOURCE`를 바꿈
- [ ] 실차 마이크 크기에서 따라 부르기의 목소리 모드 비율 (에뮬레이터의 스피커 경로는 소리가 작아 12%에 그침)
