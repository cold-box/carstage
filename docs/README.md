# CarStage 문서

CarStage의 기획, 설계, 에뮬레이터 검증 기록입니다. 앱 소개와 실행 방법은 저장소의 [README](../README.md)에 있습니다.

## 문서 목록

| 파일 | 내용 |
|---|---|
| [04-karaoke-plan-v2.md](04-karaoke-plan-v2.md) | 노래방 기획: 모드 구조, 피날레, 2인 대결·발표 모드, 사업화 리스크, 사업 모델 |
| [05-emulator-findings.md](05-emulator-findings.md) | 에뮬레이터 검증 결과: 마이크 녹음, 차량 읽기·제어의 지원 범위와 쓰기 반영, Gleo 음성 실행, TTS, 화면 배치, 출시 빌드 확인 |
| [07-convenience-features.md](07-convenience-features.md) | 생활 편의 기능: 음악 감지 실행 제안, 충전·배터리 연동, 통화 중 자동 멈춤, 자동 정리와 복원 |
| [09-voice-reaction-plan.md](09-voice-reaction-plan.md) | 목소리 반응 설계: 목소리와 음악을 나누는 방법, 환호 감지, 세 층 반응, 흥 점수, 저장·공유하지 않는 원칙 |
| [10-ui-ux-plan.md](10-ui-ux-plan.md) | UI/UX 기획: Pleos 디자인 기준(글자 체계, 터치, 대비), "차가 무대가 된다" 콘셉트, 상태별 화면 배치(가로 두 칸·세로 한 칸), 스타일 가이드 |
| [11-no-control-plan.md](11-no-control-plan.md) | 차량 제어가 막힐 때의 기획: 허용 범위별 시나리오, 제어 없이도 남는 기능, 화면·소리로 대신할 연출, 출시 빌드에서 제어 권한을 빼는 결정 |
| [12-mc-plan.md](12-mc-plan.md) | 차를 아는 MC: 말하는 때와 문장(밤·충전·착석·시각에 맞춘 인사, 곡 제목과 점수 칭찬, 대결 차례, 충전 완료), 효과음·Gleo와 겹치지 않는 규칙, MC 끄기 스위치 |
| [13-basic-flow.md](13-basic-flow.md) | 기본 시나리오: 곡을 고르면 시작하고 "그만하기"로 노래와 함께 끝나는 흐름 |
| [images/](images/) | 앱 화면 장면(`application/scene-1~5.jpg`)과 README의 무대 GIF |

번호는 팀이 문서를 쓴 순서입니다. 공개하지 않은 내부 메모(대회 준비 일정, 초기 아이디어 검토 등)는 이 저장소에 넣지 않았으므로 번호 사이가 비어 있습니다.

## 어디서부터 읽으면 좋은가요

- 제품이 무엇을 하는지: [04](04-karaoke-plan-v2.md) → [13](13-basic-flow.md)
- 차가 소리에 어떻게 반응하고 점수를 매기는지: [09](09-voice-reaction-plan.md)
- Pleos 플랫폼에서 무엇이 되고 무엇이 안 되는지: [05](05-emulator-findings.md), [11](11-no-control-plan.md)
- 화면 설계: [10](10-ui-ux-plan.md)
