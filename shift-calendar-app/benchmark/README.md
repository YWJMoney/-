# 시작 시간 재기와 Baseline Profile (1.23 M, 순번 324-5)

실제 휴대폰(Android 9 이상, USB 디버깅 켬)을 연결하고 `shift-calendar-app/`에서 돌려요. CI는 이 모듈을 만들지 않아요.

```bash
# 1. 시작 시간 (프로필 없이 / 있이, 각 5번). 결과는 benchmark/build/outputs/connected_android_test_additional_output/
sh ./gradlew :benchmark:connectedBenchmarkAndroidTest -P android.testInstrumentationRunnerArguments.class=com.bamnat.benchmark.StartupBenchmark

# 2. Baseline Profile 만들기
sh ./gradlew :benchmark:connectedBenchmarkAndroidTest -P android.testInstrumentationRunnerArguments.class=com.bamnat.benchmark.BaselineProfileGenerator
```

2번이 끝나면 결과 폴더의 `BaselineProfileGenerator_generate-baseline-prof.txt`를 `app/src/main/baseline-prof.txt`로 바꿔 넣어요.
지금 파일은 손으로 쓴 것이라 패키지 전체를 덮어요(검토 A4). 바꾼 뒤 1번을 다시 돌려 '프로필 있이'가 빨라졌는지 봐요.

- 앱은 `benchmark` 빌드(출시와 같이 줄인 코드, 디버그 키로 서명, profileable)로 깔려요. 이 빌드는 올리지 않아요.
- 측정 숫자(`timeToInitialDisplayMs`, `timeToFullDisplayMs` 중앙값)는 `계획/1.23-M-품질-성능-시험-개발계획.md`의 숫자 표에 적어요.
