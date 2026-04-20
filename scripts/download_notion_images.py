#!/usr/bin/env python3
"""
Notion S3 이미지 다운로더 (Personal_Study 레포 전용)

동작:
  1) Personal_Study/*.md 안의 Notion S3 서명 URL 이미지를 전부 스캔
  2) Personal_Study/<파일명>/image_N.<확장자> 로 다운로드
  3) .md 파일 내 URL을 상대 경로로 교체

실행:
  python scripts/download_notion_images.py

주의:
  Notion S3 URL(prod-files-secure.s3.us-west-2.amazonaws.com)은 약 1시간 후 만료됩니다.
  Notion에서 fetch한 직후에 바로 실행하세요. 만료된 경우 다시 Notion에서 해당 페이지를
  fetch하여 .md 파일을 재생성한 뒤 이 스크립트를 돌리시면 됩니다.
"""
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

try:
    import requests
except ImportError:
    print("requests 모듈이 필요합니다. 설치: pip install requests")
    sys.exit(1)

ROOT = Path(__file__).resolve().parent.parent
PS_DIR = ROOT / "Personal_Study"

# Notion S3 서명 URL 매칭 (![alt](url) 형태)
IMG_PATTERN = re.compile(
    r'!\[([^\]]*)\]\((https://prod-files-secure\.s3\.us-west-2\.amazonaws\.com/[^)\s]+)\)'
)


def guess_ext(url: str, content_type: str = "") -> str:
    path = urlparse(url).path.lower()
    for ext in (".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp"):
        if ext in path:
            return ".jpg" if ext == ".jpeg" else ext
    ct = content_type.lower()
    if "png" in ct:
        return ".png"
    if "jpeg" in ct or "jpg" in ct:
        return ".jpg"
    if "gif" in ct:
        return ".gif"
    if "svg" in ct:
        return ".svg"
    if "webp" in ct:
        return ".webp"
    return ".png"


def process_file(md_path: Path) -> tuple[int, int, int]:
    content = md_path.read_text(encoding="utf-8")
    matches = list(IMG_PATTERN.finditer(content))
    if not matches:
        return 0, 0, 0

    topic_dir_name = md_path.stem  # "Python.md" -> "Python"
    img_dir = PS_DIR / topic_dir_name
    img_dir.mkdir(parents=True, exist_ok=True)

    total = len(matches)
    success = 0
    failed = 0

    # replace_all 방지 위해 역순으로 처리 (오프셋 변동 방지)
    replacements = []
    for idx, m in enumerate(matches, start=1):
        alt = m.group(1)
        url = m.group(2)
        try:
            resp = requests.get(url, timeout=30)
            resp.raise_for_status()
            ext = guess_ext(url, resp.headers.get("Content-Type", ""))
            filename = f"image_{idx}{ext}"
            (img_dir / filename).write_bytes(resp.content)
            rel_path = f"./{topic_dir_name}/{filename}"
            replacements.append((m.group(0), f"![{alt}]({rel_path})"))
            success += 1
            print(f"  ✓ [{idx:>3}/{total}] {filename}")
        except Exception as e:
            failed += 1
            msg = str(e)[:120]
            print(f"  ✗ [{idx:>3}/{total}] 실패: {msg}")

    if replacements:
        for old, new in replacements:
            content = content.replace(old, new, 1)
        md_path.write_text(content, encoding="utf-8")

    return total, success, failed


def main() -> int:
    if not PS_DIR.exists():
        print(f"ERROR: {PS_DIR} 디렉토리를 찾을 수 없습니다.", file=sys.stderr)
        return 1

    md_files = sorted(PS_DIR.glob("*.md"))
    print(f"{len(md_files)}개 .md 파일 검사\n")

    grand_total = grand_success = grand_failed = 0
    for md in md_files:
        cnt = len(IMG_PATTERN.findall(md.read_text(encoding="utf-8")))
        if cnt == 0:
            continue
        print(f"[{md.name}] {cnt}개 이미지")
        t, s, f = process_file(md)
        grand_total += t
        grand_success += s
        grand_failed += f
        print()

    print("=" * 40)
    print(f"총 이미지:   {grand_total}")
    print(f"다운로드 성공: {grand_success}")
    print(f"실패:        {grand_failed}")

    if grand_failed > 0:
        print("\n실패 원인 추정:")
        print("  - Notion S3 서명 URL 만료 (fetch 후 1시간 초과)")
        print("  - 네트워크 문제 / 프록시 차단")
        print("  - 해결: Notion에서 해당 페이지를 다시 동기화한 뒤 재실행")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
