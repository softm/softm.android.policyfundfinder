# 지원한눈 Android

`https://policyfundfinder.designboard.net/`을 Android WebView로 제공하는 Google Play용 하이브리드 앱입니다.

- 애플리케이션 ID: `softm.android.policyfundfinder`
- 앱 이름: 지원한눈 - 정책자금·지원사업
- 현재 버전: `1.0.0` (`versionCode 1`)
- SDK: minSdk 24 / targetSdk 36 / compileSdk 36

## 저장소 구성

- `app/`: Android 애플리케이션 모듈
- `gradle/`, `gradlew`, `gradlew.bat`: Gradle Wrapper
- `store/`: Google Play 등록용 아이콘, 그래픽, 스크린샷과 한국어 문안
- `BUILD_NOTES.txt`: 빌드 및 제출 전 확인 사항
- `PLAY_RELEASE_STATUS.txt`: 현재 Play Console 배포 상태
- `signing.properties.example`: 로컬 서명 설정 예시

`settings.gradle`에는 `:app` 모듈만 포함됩니다. 같은 로컬 폴더에 남아 있는 이전 프로젝트 디렉터리는 `.gitignore`에서 제외합니다.

## 개발 빌드

JDK 17과 Android SDK 36이 필요합니다.

```sh
./gradlew :app:lintDebug :app:assembleDebug
```

디버그 APK는 `app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.

## 릴리스 빌드

예시 파일을 복사해 로컬 전용 `signing.properties`를 작성합니다.

```sh
cp signing.properties.example signing.properties
```

```properties
storeFile=/absolute/path/to/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

`signing.properties`와 키스토어 파일은 Git에 포함하지 않습니다. 설정 후 다음 명령으로 lint, APK, AAB를 생성합니다.

```sh
./release.sh
```

- APK: `app/build/outputs/apk/release/app-release.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

## 새 GitHub 저장소로 최초 푸시

GitHub에서 저장소를 먼저 만든 경우:

```sh
git remote add origin git@github.com:softm/softm.android.policyfundfinder.git
git push -u origin main
```

GitHub CLI로 저장소 생성과 최초 푸시를 함께 수행하려면 공개 범위를 결정한 뒤 둘 중 하나를 실행합니다.

```sh
# 비공개 저장소
gh repo create softm/softm.android.policyfundfinder --private --source=. --remote=origin --push

# 공개 저장소
gh repo create softm/softm.android.policyfundfinder --public --source=. --remote=origin --push
```

실제 서명 파일, `local.properties`, 빌드 결과, `.serena/` 및 로컬의 이전 프로젝트 폴더는 푸시 대상에서 제외됩니다.
