Notion 원본: https://app.notion.com/p/3db5a06fd6d3810f9037e217d85b3973?pvs=204

# 유니코드 정규화와 Grapheme Cluster 및 문자열 비교 정렬의 함정

> 2026-09-14 신규 주제 · 확장 대상: 문자 인코딩·문자열 처리

## 학습 목표

- 코드 포인트·코드 유닛·그래핌 클러스터를 구분하고 각 층위에서 "문자 수"를 정확히 센다.
- NFC/NFD/NFKC/NFKD 의 동치 관계를 판별해 한글 완성형·조합형 혼재 데이터를 복구한다.
- Java `Collator` 와 DB collation 의 강도·정규화 동작을 매칭시켜 정렬·조인 결과를 재현 가능하게 만든다.
- 정규화 순서 뒤바뀜과 호모글리프로 인한 우회를 차단하는 입력 경계 규칙을 설계한다.

## 1. 세 개의 층위: 코드 유닛, 코드 포인트, 그래핌 클러스터

문자열을 다룰 때 "길이"라는 단어는 최소 세 가지 서로 다른 값을 가리킨다. 가장 아래에 **코드 유닛(code unit)** 이 있다. 인코딩이 한 번에 처리하는 고정 폭 단위로, UTF-8 은 8비트, UTF-16 은 16비트, UTF-32 는 32비트다. 그 위에 **코드 포인트(code point)** 가 있다. U+0000 부터 U+10FFFF 까지의 정수값이고, 유니코드가 문자에 부여한 추상적 번호다. 맨 위에 **그래핌 클러스터(grapheme cluster)** 가 있다. 사용자가 "한 글자"로 인식하고 백스페이스 한 번으로 지우기를 기대하는 단위다.

Java 의 `String` 은 내부적으로 UTF-16 시퀀스이므로 `length()` 는 **코드 유닛 수**를 반환한다. Java 1.0 이 설계되던 1990년대 초에는 유니코드가 16비트 고정 폭이라고 믿었고, 나중에 U+FFFF 를 넘는 보조 평면(supplementary plane)이 추가되면서 하위 호환을 위해 **서로게이트 페어(surrogate pair)** 가 도입됐다. U+10000 이상의 코드 포인트는 상위 서로게이트(U+D800~U+DBFF)와 하위 서로게이트(U+DC00~U+DFFF) 두 개의 코드 유닛으로 표현된다. 변환식은 `cp = 0x10000 + (high - 0xD800) * 0x400 + (low - 0xDC00)` 이다. JDK 9 의 Compact Strings 이후 `String` 은 내부적으로 `byte[]` 와 LATIN1/UTF16 coder 를 쓰지만, 외부로 노출되는 API 의 인덱스 의미는 여전히 UTF-16 코드 유닛이다.

```java
public final class LengthLayers {
    public static void main(String[] args) {
        String flag = "🇰🇷"; // 🇰🇷 = U+1F1F0 U+1F1F7
        String family = "👨‍👩‍👧‍👦";

        System.out.println(flag.length());                  // 4  (UTF-16 code unit)
        System.out.println(flag.codePointCount(0, flag.length()));   // 2  (code point)
        System.out.println(flag.getBytes(java.nio.charset.StandardCharsets.UTF_8).length); // 8

        System.out.println(family.length());                // 11
        System.out.println(family.codePointCount(0, family.length())); // 7
        // 화면상으로는 둘 다 "한 글자"다.
    }
}
```

UTF-8 은 코드 포인트 범위에 따라 1~4바이트를 쓴다. ASCII(U+0000~U+007F)는 1바이트, 라틴 확장·키릴(U+0080~U+07FF)은 2바이트, 한글 완성형을 포함한 BMP 대부분(U+0800~U+FFFF)은 3바이트, 이모지 같은 보조 평면(U+10000~U+10FFFF)은 4바이트다. 이 비대칭이 실무에서 바로 문제가 된다. Oracle `VARCHAR2(20)` 는 기본이 BYTE 시맨틱이라 한글 6글자를 넣으면 18바이트를 먹고, 뒤에 설명할 NFD 형태로 들어오면 같은 6글자가 36~54바이트가 되어 `ORA-12899` 로 터진다.

| 단위 | "가" (NFC) | "가" (NFD) | "🇰🇷" | "👍🏽" |
|---|---|---|---|---|
| UTF-8 바이트 | 3 | 6 | 8 | 8 |
| UTF-16 코드 유닛 (Java `length()`) | 1 | 2 | 4 | 4 |
| UTF-32 / 코드 포인트 | 1 | 2 | 2 | 2 |
| 그래핌 클러스터 | 1 | 1 | 1 | 1 |

## 2. 정준 동치와 호환 동치, 그리고 네 가지 정규화 형식

유니코드는 "같은 문자"를 두 단계로 정의한다. **정준 동치(canonical equivalence)** 는 시각적·의미적으로 완전히 동일해서 어떤 상황에서도 구분하면 안 되는 관계다. U+00C5(Å, LATIN CAPITAL LETTER A WITH RING ABOVE)와 U+0041 U+030A(A + COMBINING RING ABOVE)는 정준 동치다. **호환 동치(compatibility equivalence)** 는 의미는 같지만 서식이 달라서 문맥에 따라 구분해야 할 수도 있는 관계다. U+FB01(ﬁ 합자)과 "fi", U+2460(①)과 "1", U+FF21(Ａ)과 "A" 가 그렇다. 정준 동치는 호환 동치의 부분집합이다.

UAX #15 는 이 두 동치 관계를 분해(decomposition)와 합성(composition)에 조합해 네 가지 정규화 형식을 정의한다.

| 형식 | 분해 | 재합성 | 결과 특성 | 주 용도 |
|---|---|---|---|---|
| NFD | 정준 | 없음 | 가장 긴 분해 형태 | 결합 문자 단위 처리, macOS 파일 시스템 |
| NFC | 정준 | 정준 합성 | 가장 짧은 합성 형태 | 저장·전송 기본형, W3C 권고 |
| NFKD | 호환 | 없음 | 서식 정보 소실 + 분해 | 검색 인덱싱 토큰 |
| NFKC | 호환 | 정준 합성 | 서식 정보 소실 + 합성 | 식별자 정규화, 검색 키 |

핵심 원칙은 **NFC/NFD 는 왕복(round-trip) 손실이 없지만 NFKC/NFKD 는 되돌릴 수 없다**는 것이다. `NFKC("①②③")` 은 `"123"` 이 되고 원본을 복원할 방법은 없다. 그래서 NFKC 는 검색 키나 중복 검사용 파생 값에만 쓰고, 원본은 NFC 로 따로 보관하는 이중 컴럼 전략이 안전하다.

```java
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;

public final class NormalizationForms {
    public static void main(String[] args) {
        String composed = "Å";          // Å
        String decomposed = "Å";       // A + COMBINING RING ABOVE

        System.out.println(composed.equals(decomposed));                 // false
        System.out.println(Normalizer.normalize(composed, Normalizer.Form.NFD)
                .equals(decomposed));                                    // true

        String ligature = "ﬁle";        // ﬁle
        System.out.println(Normalizer.normalize(ligature, Normalizer.Form.NFC));  // ﬁle (변화 없음)
        System.out.println(Normalizer.normalize(ligature, Normalizer.Form.NFKC)); // file

        String halfWidth = "ﾡ";         // ﾡ HALFWIDTH HANGUL LETTER KIYEOK
        String nfkc = Normalizer.normalize(halfWidth, Normalizer.Form.NFKC);
        System.out.printf("U+%04X%n", nfkc.codePointAt(0)); // U+1100 (조합용 초성, ㄱ 아님)
    }
}
```

마지막 예제가 한글에서 특히 위험한 함정이다. 호환 자모 U+3131(ㄱ)은 호환 분해가 U+1100(조합용 초성 기역)으로 정의도 있어서, 검색어에 NFKC 를 걸면 사용자가 입력한 "ㄱ"이 조합용 자모로 바뀜다. 조합용 자모는 단독으로 렌더링하면 폭이 0에 가깝거나 깨져 보이고, 인덱스에 저장된 U+3131 과 매칭되지 않는다. 자모 단위 초성 검색을 지원한다면 NFKC 는 쓰면 안 된다.

## 3. 한글: 완성형과 조합형, 그리고 macOS 파일명 사고

한글은 유니코드에서 두 방식으로 표현된다. **완성형(precomposed)** 은 U+AC00~U+D7A3 범위의 11,172개 Hangul Syllables 이고, **조합형(conjoining jamo)** 은 초성 U+1100~U+1112(19개), 중성 U+1161~U+1175(21개), 종성 U+11A8~U+11C2(27개 + 종성 없음)의 조합이다. 둘은 정준 동치다. 따라서 `NFD("가") = U+1100 U+1161`, `NFC(U+1100 U+1161) = U+AC00` 이다.

주목할 점은 이 변환이 테이블 룩업이 아니라 **산술 공식**이라는 것이다. UAX #15 의 Hangul Syllable Decomposition 알고리즘은 `SBase = 0xAC00`, `LBase = 0x1100`, `VBase = 0x1161`, `TBase = 0x11A7`, `VCount = 21`, `TCount = 28`, `NCount = 588` 상수로 O(1) 에 동작한다. 11,172개 음절을 테이블로 들고 있을 필요가 없어서 정규화 구현 비용이 라틴 결합 문자보다 오히려 싸다.

```java
public final class HangulDecomposer {
    private static final int S_BASE = 0xAC00;
    private static final int L_BASE = 0x1100;
    private static final int V_BASE = 0x1161;
    private static final int T_BASE = 0x11A7;
    private static final int V_COUNT = 21;
    private static final int T_COUNT = 28;
    private static final int N_COUNT = V_COUNT * T_COUNT; // 588

    public static int[] decompose(int syllable) {
        int sIndex = syllable - S_BASE;
        if (sIndex < 0 || sIndex >= 11172) {
            return new int[] { syllable };
        }
        int lead = L_BASE + sIndex / N_COUNT;
        int vowel = V_BASE + (sIndex % N_COUNT) / T_COUNT;
        int trail = T_BASE + sIndex % T_COUNT;
        if (sIndex % T_COUNT == 0) {
            return new int[] { lead, vowel };
        }
        return new int[] { lead, vowel, trail };
    }

    public static void main(String[] args) {
        for (int cp : decompose('한')) { // U+D55C
            System.out.printf("U+%04X ", cp); // U+1112 U+1161 U+11AB
        }
    }
}
```

실무에서 이 차이가 가장 아프게 드러나는 곳이 **파일명**이다. macOS 의 HFS+ 는 파일명을 NFD 변형으로 정규화해서 저장했고, APFS 는 주어진 바이트열을 보존하지만 macOS 상위 계층과 Finder 가 만들어내는 이름은 여전히 NFD 계열인 경우가 많다. Windows(NTFS)와 Linux(ext4/XFS)는 정규화를 전혀 하지 않고 바이트열을 그대로 저장하되, 입력이 통상 NFC 로 들어온다. 그 결과 macOS 에서 만든 "보고서.pdf" 를 ZIP 으로 압축해 Windows 에서 풀면 자모가 분리되어 "ㅂㅗㄱㅗㅅㅓ.pdf" 처럼 보이고, S3 에 올린 뒤 NFC 키로 조회하면 404 가 난다. 해결책은 단순하다. **파일명은 업로드 수신 시점에 무조건 NFC 로 정규화한 뒤 저장 키로 쓴다.**

```java
import java.text.Normalizer;
import org.springframework.web.multipart.MultipartFile;

public final class UploadKeyResolver {
    public String resolveStorageKey(MultipartFile file) {
        String raw = file.getOriginalFilename();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("filename is required");
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String sanitized = nfc.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        return "uploads/" + sanitized;
    }
}
```

## 4. Grapheme Cluster Boundary: "한 글자"를 세는 유일하게 옛은 방법

UAX #29 는 코드 포인트 사이의 경계를 GB1~GB999 규칙으로 정의한다. 실무에서 중요한 규칙은 네 가지다. 결합 문자(`Extend`, 예: U+0301 COMBINING ACUTE ACCENT)는 앞 문자에 붙는다(GB9). 한글 조합형 자모는 L·V·T 순서로 하나의 음절을 이뢬다(GB6~GB8). 국기 이모지는 Regional Indicator 를 **짝수 개씩 묶는다**(GB12/GB13) — 🇰🇷는 U+1F1F0 U+1F1F7 두 개가 한 클러스터다. 그리고 GB11 은 `\p{Extended_Pictographic}` 사이에 ZWJ(U+200D)가 있으면 붙인다. 👨‍👩‍👧‍👦(가족)는 이모지 4개 + ZWJ 3개 = 7 코드 포인트, 11 UTF-16 코드 유닛이지만 그래핌 클러스터는 1이다. 스킨톤도 마찬가지로 👍🏽 = U+1F44D + U+1F3FD(EMOJI MODIFIER FITZPATRICK TYPE-4) 2 코드 포인트, 1 클러스터다.

```java
import java.text.BreakIterator;

public final class GraphemeCounter {
    public static int count(String text) {
        BreakIterator it = BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        it.setText(text);
        int n = 0;
        while (it.next() != BreakIterator.DONE) {
            n++;
        }
        return n;
    }

    public static String truncate(String text, int maxGraphemes) {
        BreakIterator it = BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        it.setText(text);
        int end = it.first();
        for (int i = 0; i < maxGraphemes && it.next() != BreakIterator.DONE; i++) {
            end = it.current();
        }
        return text.substring(0, end);
    }
}
```

`BreakIterator.getCharacterInstance()` 는 결합 문자와 한글 자모는 안정적으로 처리하지만, GB11(이모지 ZWJ 시퀀스) 지원 여부는 JDK 버전과 번들된 CLDR 데이터에 따라 다르다. 운영 JDK 에서 `count("👨‍👩‍👧‍👦")` 가 1 이 아니라 4 나 7 로 나오면 ICU4J 의 `com.ibm.icu.text.BreakIterator` 로 교체해야 한다. `String.substring()` 으로 잘라내는 코드는 이런 검증 없이 서로게이트 페어 한가운데를 절단해 U+FFFD 를 만들어낸다 — 닉네임 미리보기, 푸시 알림 본문 요약, SMS 90바이트 절단 로직이 전형적인 사고 지점이다.

JavaScript 는 `Intl.Segmenter` 가 UAX #29 확장 그래핌 클러스터를 정확히 구현한다. Node 16+, Chrome 87+, Safari 14.1+, Firefox 125+ 에서 쓸 수 있다.

```javascript
const seg = new Intl.Segmenter('ko', { granularity: 'grapheme' });

function graphemeLength(s) {
  return [...seg.segment(s.normalize('NFC'))].length;
}

console.log('👨‍👩‍👧‍👦'.length);            // 11 (UTF-16 code units)
console.log([...'👨‍👩‍👧‍👦'].length);       // 7  (code points, iterator)
console.log(graphemeLength('👨‍👩‍👧‍👦'));   // 1

const nfd = '가';
console.log(nfd === '가');                  // false
console.log(nfd.normalize('NFC') === '가'); // true
console.log(graphemeLength(nfd));           // 1
```

## 5. 비교와 정렬: 코드 포인트 순서는 정렬이 아니다

`String.compareTo()` 와 `Arrays.sort()` 기본 동작은 UTF-16 코드 유닛 값을 비교하는 **binary 정렬**이다. 빠르고 결정적이지만 사람이 기대하는 순서와는 다르다. ASCII 에서는 모든 대문자가 모든 소문자보다 앞서므로 `"Zebra" < "apple"` 이고, 한글 조합형이 섮이면 U+1100 대의 자모가 U+AC00 대의 완성형보다 앞서서 같은 단어가 목록의 정반대 끝에 나타난다.

언어 인식 정렬은 **collation** 이 담당한다. 유니코드 대조 알고리즘(UCA)은 각 문자에 다중 레벨 가중치를 부여하고, 언어별 조정은 CLDR 이 제공한다. Java 의 `java.text.Collator` 는 강도(strength)로 어느 레벨까지 볼지 정한다.

| 강도 | 구분 대상 | 예시 판정 |
|---|---|---|
| PRIMARY | 기본 문자만 | "resume" = "résumé" = "RESUME" |
| SECONDARY | + 악센트 | "resume" ≠ "résumé", "resume" = "RESUME" |
| TERTIARY | + 대소문자 | "resume" ≠ "RESUME" (기본값) |
| IDENTICAL | + 코드 포인트 | NFD/NFC 까지 구분 |

주의할 점은 `Collator` 의 기본 `decomposition` 이 `NO_DECOMPOSITION` 이라는 것이다. 즉 **입력이 NFD 면 Collator 도 틀린 답을 낸다.** `setDecomposition(Collator.CANONICAL_DECOMPOSITION)` 을 켜거나(느려진다), 입력 경계에서 NFC 로 정규화해두고 기본값을 쓰는 편이 낫다. 그리고 N개 항목을 정렬할 때 `Collator.compare` 는 O(N log N) 번 호출되므로 비싸다 — `CollationKey` 로 한 번씩만 변환해 두면 비교는 바이트 배열 비교로 떨어진다.

```java
import java.text.Collator;
import java.text.CollationKey;
import java.text.Normalizer;
import java.util.*;

public final class KoreanSorter {
    public static List<String> sort(List<String> names) {
        Collator collator = Collator.getInstance(Locale.KOREA);
        collator.setStrength(Collator.TERTIARY);

        List<CollationKey> keys = new ArrayList<>(names.size());
        for (String name : names) {
            String nfc = Normalizer.normalize(name, Normalizer.Form.NFC);
            keys.add(collator.getCollationKey(nfc));
        }
        keys.sort(Comparator.naturalOrder());

        List<String> result = new ArrayList<>(keys.size());
        for (CollationKey key : keys) {
            result.add(key.getSourceString());
        }
        return result;
    }

    public static void main(String[] args) {
        List<String> raw = Arrays.asList("가나", "가", "Zebra", "apple");
        System.out.println(sort(raw));
        // binary 정렬이었다면 "Zebra", "apple", "가", "가나" 순으로 흩어진다.
    }
}
```

## 6. DB 계층: collation 불일치가 인덱스를 죽인다

**MySQL 은 어떤 collation 에서도 유니코드 정규화를 수행하지 않는다.** 이 한 문장이 MySQL 한글 처리의 모든 사고를 설명한다. `utf8mb4_0900_ai_ci` 는 UCA 9.0.0 기반으로 악센트 무시(ai)·대소문자 무시(ci)를 하지만, NFC `'가'`(1문자, 3바이트)와 NFD `'가'`(2문자, 6바이트)는 **여전히 다른 값**이다. `CHAR_LENGTH('가')` 는 1, NFD 형태는 2를 반환하므로 `VARCHAR(10)` 컴럼에 한글 5글자만 들어가는 상황도 생긴다.

| collation | 대소문자 | 악센트 | 공백 패딩 | 정규화 |
|---|---|---|---|
| `utf8mb4_0900_ai_ci` | 무시 | 무시 | NO PAD | 없음 |
| `utf8mb4_0900_as_cs` | 구분 | 구분 | NO PAD | 없음 |
| `utf8mb4_unicode_ci` | 무시 | 무시 | PAD SPACE | 없음 |
| `utf8mb4_bin` | 구분 | 구분 | PAD SPACE | 없음 |

`PAD SPACE` 계열에서는 `'a' = 'a  '` 가 참이 되는 반면 `_0900_` 계열(NO PAD)에서는 거짓이다. 레거시 테이블과 신규 테이블을 조인할 때 이 차이가 결과 행 수를 바꿈다. 더 흔한 사고는 서로 다른 collation 컴럼을 조인해 `ERROR 1267 (HY000): Illegal mix of collations` 를 만나는 경우다. 급한 마음에 `COLLATE` 를 인덱스된 컴럼 쪽에 붙이면 에러는 사라지지만 그 컴럼의 인덱스는 더 이상 사용되지 않는다.

```sql
-- 진단: 어떤 컴럼이 다른 collation 을 쓰는지
SELECT TABLE_NAME, COLUMN_NAME, COLLATION_NAME
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = 'app' AND COLLATION_NAME IS NOT NULL
   AND COLLATION_NAME <> 'utf8mb4_0900_ai_ci';

-- 안티패턴: 인덱스된 u.name 에 COLLATE 를 씨우면 full scan
EXPLAIN SELECT * FROM users u JOIN legacy_users l
    ON u.name COLLATE utf8mb4_general_ci = l.name;   -- type: ALL

-- 처방: 컴럼 자체의 collation 을 통일 (ALGORITHM=COPY, 테이블 재작성)
ALTER TABLE legacy_users
  MODIFY name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL;

-- MySQL 은 정규화하지 않음을 확인
SELECT '가' = CONVERT(UNHEX('E18480E185A1') USING utf8mb4) AS same;  -- 0
```

PostgreSQL 은 ICU provider 로 CLDR 규칙을 직접 쓸 수 있고, `deterministic = false` 인 비결정적 collation 에서는 대소문자·악센트 무시 비교가 가능하다. 다만 비결정적 collation 컴럼에는 `LIKE` 와 정규식 연산자를 쓸 수 없어 에러가 난다 — 검색 컴럼과 정렬 컴럼을 분리해야 한다.

```sql
CREATE COLLATION ko_icu (provider = icu, locale = 'ko-KR');
CREATE COLLATION ko_ci (provider = icu, locale = 'ko-KR-u-ks-level2', deterministic = false);

CREATE TABLE members (
    id     bigserial PRIMARY KEY,
    name   text COLLATE ko_icu NOT NULL,
    login  text COLLATE ko_ci  NOT NULL UNIQUE   -- 대소문자 무시 유니크
);
CREATE INDEX idx_members_name ON members (name COLLATE ko_icu);
SELECT name FROM members ORDER BY name COLLATE ko_icu;
```

Oracle 은 세션 파라미터로 제어한다. `NLS_COMP = LINGUISTIC` 을 켜야 `NLS_SORT` 가 비교 연산에도 적용되며, 이때 일반 B-tree 인덱스는 무용지물이 되므로 `NLSSORT` 기반 함수 인덱스를 별도로 만들어야 한다.

```sql
ALTER SESSION SET NLS_SORT = 'KOREAN_M_CI';
ALTER SESSION SET NLS_COMP = 'LINGUISTIC';

CREATE INDEX idx_emp_name_ci ON employees (NLSSORT(emp_name, 'NLS_SORT=KOREAN_M_CI'));
SELECT emp_name FROM employees WHERE emp_name = '김철수' ORDER BY emp_name;
```

## 7. 보안: 호모글리프 스푸핑과 정규화 순서 뒤바뀜

**호모글리프(homoglyph)** 는 서로 다른 코드 포인트인데 렌더링이 거의 같은 문자다. 키릴 문자 `а`(U+0430)는 라틴 `a`(U+0061)와 시각적으로 구분이 안 되고, 키릴 `о`(U+043E)·`е`(U+0435)·`р`(U+0440)도 마찬가지다. 공격자는 `paypal` 대신 키릴 문자를 섮은 문자열로 계정을 만들어 관리자 계정을 사칭한다. UTS #39(Unicode Security Mechanisms)는 이에 대한 두 가지 방어를 정의한다. **혼합 스크립트 탐지(Mixed-Script Detection)** 는 한 식별자 안에 서로 다른 Script 가 섮였는지 검사하고, **skeleton 알고리즘**은 confusables 매핑으로 혼동 가능한 문자들을 대표 형태로 접어 이미 존재하는 계정과 충돌하는지 본다. UAX #31(Unicode Identifier and Pattern Syntax)은 `XID_Start`/`XID_Continue` 프로퍼티로 식별자에 허용할 문자 집합을 제한한다.

두 번째 함정은 **검증과 정규화의 순서**다. NFKC 는 서식을 접어버리므로, 검증 후에 정규화하면 검증을 통과한 안전한 문자열이 위험한 문자열로 변신한다. 전각 `＜`(U+FF1C)는 NFKC 후 `<` 가 되고, 전각 마침표 `．`(U+FF0E)는 `.` 이 되어 `．．/` 가 `../` 로 바뀐다. 반드시 **정규화 → 검증 → 저장** 순서를 지켜야 한다.

```java
import java.text.Normalizer;
import java.util.regex.Pattern;

public final class UsernamePolicy {
    private static final Pattern ALLOWED =
            Pattern.compile("^[\\p{IsHangul}\\p{Alnum}_]{2,20}$");

    public String normalizeThenValidate(String input) {
        // 1) 정규화 먼저
        String nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC);

        // 2) 혼합 스크립트 차단 (UTS #39 단순화 버전)
        if (hasMixedConfusableScripts(nfkc)) {
            throw new IllegalArgumentException("mixed script identifier rejected");
        }

        // 3) 화이트리스트 검증
        if (!ALLOWED.matcher(nfkc).matches()) {
            throw new IllegalArgumentException("invalid username");
        }
        return nfkc;
    }

    private boolean hasMixedConfusableScripts(String s) {
        boolean latin = false;
        boolean cyrillic = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.LATIN) {
                latin = true;
            } else if (script == Character.UnicodeScript.CYRILLIC) {
                cyrillic = true;
            }
            i += Character.charCount(cp);
        }
        return latin && cyrillic;
    }
}
```

여기에 더해 중복 검사는 원본이 아니라 **접힌 키(folded key)** 로 해야 한다. `username` 컴럼에 원본 NFC 를 저장하고, `username_key` 컴럼에 `NFKC + toLowerCase(Locale.ROOT) + confusable skeleton` 결과를 저장한 뒤 그 컴럼에 UNIQUE 인덱스를 건다. 참고로 `toUpperCase()` 를 기본 로케일로 호출하면 터키어 로케일에서 `i` 가 `İ`(U+0130)가 되는 이른바 Turkish-I 문제가 있으므로 반드시 `Locale.ROOT` 를 명시한다.

## 8. 파이프라인 설계 원칙과 정규화 비용

가장 견고한 규칙은 단순하다. **모든 외부 입력은 시스템 경계에서 단 한 번 NFC 로 정규화하고, 그 안쪽의 모든 코드는 입력이 NFC 임을 전제로 작성한다.** 경계란 HTTP 요청 파라미터·바디, 파일 업로드 이름, 메시지 큐 컨슈머, 배치 파일 리더, 외부 API 응답 파서다. 중간 계층에서 조건부로 정규화하기 시작하면 "여기서는 정규화됐나?" 를 매번 추론해야 하고, 그 추론이 틀리는 곳이 버그가 된다.

```java
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.text.Normalizer;
import java.io.IOException;

public class NfcNormalizingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(new NfcRequestWrapper((HttpServletRequest) req), res);
    }

    private static final class NfcRequestWrapper extends HttpServletRequestWrapper {
        NfcRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getParameter(String name) {
            return toNfc(super.getParameter(name));
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = super.getParameterValues(name);
            if (values == null) {
                return null;
            }
            String[] normalized = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                normalized[i] = toNfc(values[i]);
            }
            return normalized;
        }

        private static String toNfc(String value) {
            if (value == null || Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
                return value;
            }
            return Normalizer.normalize(value, Normalizer.Form.NFC);
        }
    }
}
```

`Normalizer.isNormalized()` 를 먼저 호출하는 것이 비용 관리의 핵심이다. UAX #15 는 각 코드 포인트에 `NFC_QC` 프로퍼티(Yes/No/Maybe)를 정의하고, 구현체는 이 quick check 로 "이미 정규화됨"을 확정할 수 있다. ASCII 와 한글 완성형은 전부 `NFC_QC = Yes` 이므로 실전 트래픽의 절대다수는 **단순 스캔 + 프로퍼티 룩업**만 하고 새 문자열 할당 없이 원본을 그대로 반환한다. 반면 `normalize()` 를 무조건 호출하면 매번 새 `String` 을 할당해 GC 압력이 올라간다.

정확한 수치는 JDK 버전·문자열 길이·힙 상태에 좀우되므로 반드시 직접 측정해야 하지만, 상대적 비율의 감각은 다음 순서로 기억하면 된다. **quick check 통과(할당 없음) < 전체 정규화(1회 할당) ≪ 정규화 + collation key 생성 ≪ DB 왕복.** 즉 정규화 비용은 거의 항상 네트워크·디스크 비용에 묻힌다. "정규화가 느릴까 봐" 생략하고 검색 불일치 버그를 떠안는 것은 거의 언제나 잘못된 트레이드오프다.

```java
import org.openjdk.jmh.annotations.*;
import java.text.Normalizer;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class NormalizeBenchmark {

    private String alreadyNfc;
    private String nfdInput;

    @Setup
    public void setUp() {
        alreadyNfc = "주문번호 2026-09-14-000123 결제완료 알림".repeat(4);
        nfdInput = Normalizer.normalize(alreadyNfc, Normalizer.Form.NFD);
    }

    @Benchmark
    public boolean quickCheckOnly() {
        return Normalizer.isNormalized(alreadyNfc, Normalizer.Form.NFC);
    }

    @Benchmark
    public String normalizeAlreadyNfc() {
        return Normalizer.normalize(alreadyNfc, Normalizer.Form.NFC);
    }

    @Benchmark
    public String normalizeFromNfd() {
        return Normalizer.normalize(nfdInput, Normalizer.Form.NFC);
    }
}
```

마지막으로 체크리스트를 남긴다. 저장은 NFC, 검색 키는 NFKC + case fold 를 별도 컴럼에 두고 UNIQUE 인덱스를 건다. 길이 제한은 그래핌 클러스터 기준으로 세되 DB 컴럼은 바이트 기준 여유를 둔다(한글 NFD 최악 9바이트/음절). 정렬은 `CollationKey` 를 캐시하거나 DB collation 에 위임하고, 애플리케이션 정렬과 DB 정렬을 섮지 않는다. `equals()` 로 사용자 입력을 비교하는 코드는 전부 정규화 여부를 감사한다.

## 참고

- [UAX #15: Unicode Normalization Forms](https://www.unicode.org/reports/tr15/)
- [UAX #29: Unicode Text Segmentation](https://www.unicode.org/reports/tr29/)
- [UAX #31: Unicode Identifier and Pattern Syntax](https://www.unicode.org/reports/tr31/)
- [UTS #39: Unicode Security Mechanisms](https://www.unicode.org/reports/tr39/)
- [UTS #10: Unicode Collation Algorithm](https://www.unicode.org/reports/tr10/)
- [Java SE 21 API: java.text.Normalizer](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/Normalizer.html)
- [Java SE 21 API: java.text.Collator](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/Collator.html)
- [Java SE 21 API: java.text.BreakIterator](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/text/BreakIterator.html)
- [MySQL 8.0 Reference Manual: Unicode Character Sets](https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-sets.html)
- [MySQL 8.0 Reference Manual: Collation Coercibility in Expressions](https://dev.mysql.com/doc/refman/8.0/en/charset-collation-coercibility.html)
- [PostgreSQL Documentation: Collation Support](https://www.postgresql.org/docs/current/collation.html)
- [Oracle Database Globalization Support Guide: Linguistic Sorting](https://docs.oracle.com/en/database/oracle/oracle-database/21/nlspg/linguistic-sorting-and-matching.html)
- [MDN: Intl.Segmenter](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl/Segmenter)
- [MDN: String.prototype.normalize()](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/String/normalize)
