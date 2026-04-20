# scripts/

## download_notion_images.py

Notion에서 동기화된 `Personal_Study/*.md` 파일의 S3 서명 이미지 URL을 다운로드하여
`Personal_Study/<파일명>/image_N.확장자` 로 저장하고, 마크다운 내 링크를 상대 경로로 교체합니다.

### 사전 준비

```powershell
python --version      # Python 3.9+
pip install requests
```

### 실행

레포 루트에서:

```powershell
python scripts\download_notion_images.py
```

### 주의

- Notion S3 URL은 약 **1시간 후 만료**됩니다 (`X-Amz-Expires=3600`).
- Claude가 Notion을 fetch한 직후에 바로 실행하세요.
- 만료된 경우 Claude에게 "Notion 다시 동기화해줘" 요청 후 이 스크립트 재실행.

### 동작 예시

```
9개 .md 파일 검사

[Python.md] 7개 이미지
  ✓ [  1/7] image_1.png
  ✓ [  2/7] image_2.png
  ...

[CDN.md] 2개 이미지
  ✓ [  1/2] image_1.png
  ...

========================================
총 이미지:   55
다운로드 성공: 55
실패:        0
```
