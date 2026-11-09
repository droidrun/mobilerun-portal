# Mobilerun Portal — 한국어 UI 패치

## 적용 기준

- 상위 프로젝트: https://github.com/droidrun/mobilerun-portal
- 기준 태그: v0.8.2, 커밋 49d92110ed9faeaad4c8f65b681c4a3012b1de25
- 현지화 브랜치: feature/korean-ui
- 작업 디렉터리: C:\Mobilerun-Portal-KO
- 라이선스: GNU AGPL-3.0-or-later, 프로젝트의 원본 LICENSE와 저작권 표시 유지
- 기존 Z Fold7에 설치된 공식 Mobilerun Portal은 수정하거나 제거하지 않음

## 현지화 범위

- app/src/main/res/values-ko/strings.xml: 공식 UI 문자열 355개 한글 번역
- app/src/main/res/values/strings_layout_ui.xml 및 values-ko 동명 파일: 레이아웃에 직접 있던 영어 레이블 81종을 현지화
- app/src/main/res/values/strings_kotlin_ui.xml 및 values-ko 동명 파일: 대화상자 등에 직접 있던 문자열 52종을 현지화
- Kotlin UI 코드에서 문자열을 리소스로 참조하도록 총 68개 위치 변경
- 원본/한국어 리소스 각 488개, 키·치환 기호·줄바꿈 검증 일치
- Android 시스템 언어가 한국어일 때 한국어 UI 사용, 그 외 언어는 기존 기본 문자열 사용
- 패키지 이름, API, 접근성 서비스, 설치 및 권한 관련 동작은 변경하지 않음

## 검증 방법

정적 검사:
  python scripts\validate_ko.py
  python scripts\audit_hardcoded_ui.py

Gradle 빌드 및 단위 테스트(Windows PowerShell):
  $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot'
  $env:ANDROID_HOME='D:\Android\Sdk'
  $env:ANDROID_SDK_ROOT='D:\Android\Sdk'
  .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --no-daemon --console plain

Gradle 8.13과 Android Studio의 JDK 25 조합에서는 오류가 나타날 수 있어 JDK 21을 명시함.

## APK 서명 및 설치 주의

로컬에서 생성하는 app-debug.apk는 개발용 디버그 서명 APK입니다. 공식 Mobilerun Portal과 서명이 다르므로 원래 앱 위에 업데이트할 수 있다는 보장이 없습니다. 기존 앱을 삭제하거나 데이터를 초기화하면서 설치하면 안 됩니다. 현재 인증 토큰, 역방향 연결, 접근성 서비스 설정을 보존해야 합니다.

한국어 버전을 운영 기기에 배포하려면 공식 프로젝트에 번역을 기여해 공식 서명 업데이트로 반영하는 것이 가장 안전합니다. 또는 별도 테스트 기기/에뮬레이터에서 검증한 후 백업 및 사용자 승인을 거쳐 별도 이전 절차를 마련해야 합니다.

수정 APK를 제3자에게 배포하거나 네트워크로 제공할 경우 AGPL-3.0-or-later의 소스 제공 및 고지 의무를 준수해야 합니다.

## 주요 작업 파일

- scripts/ui_literals_ko.json, scripts/kotlin_literals_ko.json: 영어·한국어 대응표
- scripts/localize_layout_literals.py: 레이아웃의 영어 텍스트를 언어 리소스로 변경
- scripts/localize_kotlin_ui.py: 사용자 표시 대화상자 및 토스트를 언어 리소스로 변경
- scripts/validate_ko.py: 리소스 키·포맷 치환·줄바꿈 검사
- scripts/audit_hardcoded_ui.py: 사용자에게 표시될 수 있는 영어 직접 입력 감사

단말에서 발생하는 시스템별 오류 문자열이나 외부 API가 내려주는 메시지는 일부 영어로 표시될 수 있으며, 실제 기기 UI 검증은 별도 과제입니다.
