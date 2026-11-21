Notion 원본: https://www.notion.so/41f5a06fd6d3819294e7de1c5e0da3d9

# 문자 인코딩: UTF-8·UTF-16·CP949 바이트 구조와 mojibake 진단, BOM, Java Charset 디코딩 동작

> 2026-11-21 신규 주제 · 확장 대상: JAVA(String·I/O), Python(bytes·str), WEB(Content-Type charset)

## 학습 목표

- 같은 글자가 UTF-8, UTF-16, CP949 에서 어떤 바이트가 되는지 출력해 코드 포인트와 인코딩을 구분한다
- UTF-8 의 선두 바이트와 연속 바이트 구조를 비트로 확인하고, 디코더가 거부하는 바이트열 다섯 가지를 재현한다
- 깨진 글자의 모양에서 "어떤 바이트를 어떤 인코딩으로 잘못 읽었는지" 를 역추적하고 복구 가능 여부를 판정한다
- Java 의 `new String(bytes, charset)` 이 오류를 숨기는 방식과 BOM 을 제거하지 않는 동작을 실행 결과로 확인한다

## 1. 코드 포인트와 인코딩은 다른 층이다

유니코드는 글자마다 번호(코드 포인트)를 준다. `가` 는 U+AC00 이다. 인코딩은 그 번호를 바이트열로 적는 규칙이다. 같은 번호라도 규칙이 다르면 바이트가 달라지고, 유니코드와 무관하게 만들어진 CP949 는 자체 표에서 `가` 를 찾아 바이트를 정한다.

```python
def hx(b): return b.hex(" ").upper()

for ch in ["A", "é", "가", "똠", "😀"]:
    row = [f"U+{ord(ch):04X}"]
    for enc in ["utf-8", "utf-16-be", "cp949", "euc-kr"]:
        try: row.append(hx(ch.encode(enc)))
        except UnicodeEncodeError: row.append("인코딩 불가")
    print(ch, " | ".join(row))
```

```text
A U+0041 | 41 | 00 41 | 41 | 41
é U+00E9 | C3 A9 | 00 E9 | 인코딩 불가 | 인코딩 불가
가 U+AC00 | EA B0 80 | AC 00 | B0 A1 | B0 A1
똠 U+B620 | EB 98 A0 | B6 20 | 8C 63 | A4 D4 A4 A8 A4 C7 A4 B1
😀 U+1F600 | F0 9F 98 80 | D8 3D DE 00 | 인코딩 불가 | 인코딩 불가
```

열은 코드 포인트, UTF-8, UTF-16BE, CP949, EUC-KR 순서다. ASCII 범위는 UTF-8 과 CP949 에서 바이트가 같다. 영문과 숫자만 있는 파일이 인코딩을 잘못 지정해도 멀쩡해 보이는 이유이고, 한글이 처음 들어간 날 문제가 드러나는 이유다. `가` 는 UTF-8 에서 3 바이트, CP949 에서 2 바이트다. `é` 와 이모지는 CP949 표에 없어 인코딩할 수 없다.

## 2. UTF-8 은 첫 바이트가 길이를 알려 준다

```python
for ch in ["A", "é", "가", "😀"]:
    print(ch, " ".join(f"{b:08b}" for b in ch.encode("utf-8")))
```

```text
A 01000001
é 11000011 10101001
가 11101010 10110000 10000000
😀 11110000 10011111 10011000 10000000
```

| 코드 포인트 범위 | 바이트 수 | 비트 패턴 |
|---|---|---|
| U+0000 ~ U+007F | 1 | `0xxxxxxx` |
| U+0080 ~ U+07FF | 2 | `110xxxxx 10xxxxxx` |
| U+0800 ~ U+FFFF | 3 | `1110xxxx 10xxxxxx 10xxxxxx` |
| U+10000 ~ U+10FFFF | 4 | `11110xxx 10xxxxxx 10xxxxxx 10xxxxxx` |

선두 바이트의 앞쪽 1 의 개수가 전체 길이이고, 연속 바이트는 항상 `10` 으로 시작한다. `가`(U+AC00, 이진수 `1010 110000 000000`)를 3 바이트 틀에 채우면 `1110`+`1010`, `10`+`110000`, `10`+`000000` 이 되어 위 출력과 일치한다. 이 구조에서 세 가지 성질이 나온다. 어느 바이트에서 읽기 시작해도 `10` 으로 시작하는 바이트를 건너뛰면 다음 글자의 경계를 찾을 수 있다. 멀티바이트 글자의 어떤 바이트도 ASCII 범위(최상위 비트 0)와 겹치지 않는다. 바이트 값 순서로 정렬하면 코드 포인트 순서와 같다.

두 번째 성질은 CP949 에 없다. 1절 출력에서 `똠` 의 CP949 바이트는 `8C 63` 인데 `0x63` 은 ASCII `c` 다. 바이트 단위로 `c` 를 찾거나 구분자로 자르는 코드는 CP949 문자열에서 글자의 뒷바이트를 잘못 집을 수 있다. UTF-8 에서는 이런 오탐이 생기지 않는다.

## 3. 디코더가 거부하는 바이트열

```python
for label, raw in [("연속 바이트만", b"\x80"), ("잘린 시퀀스", "가".encode()[:2]), ("overlong '/'", b"\xc0\xaf"),
                   ("서로게이트 U+D800", b"\xed\xa0\x80"), ("U+10FFFF 초과", b"\xf4\x90\x80\x80")]:
    try: raw.decode("utf-8"); print(label, "통과")
    except UnicodeDecodeError as e: print(f"{label}: {e.reason} / replace={raw.decode('utf-8', 'replace')!r}")
```

```text
연속 바이트만: invalid start byte / replace='�'
잘린 시퀀스: unexpected end of data / replace='�'
overlong '/': invalid start byte / replace='��'
서로게이트 U+D800: invalid continuation byte / replace='���'
U+10FFFF 초과: invalid continuation byte / replace='����'
```

비트 틀에 맞는다고 모두 유효한 것은 아니다. `C0 AF` 는 `/`(U+002F)를 2 바이트로 늘려 적은 overlong 형태다. 틀에는 맞지만 최단 형태가 아니어서 거부된다. 이것을 받아 주면 "`/` 가 없는지 검사" 하는 필터를 `C0 AF` 로 통과한 뒤 디코딩 단계에서 `/` 로 바뀌는 우회가 가능해지므로, RFC 3629 는 최단 형태만 허용한다. UTF-16 전용인 서로게이트 영역과 U+10FFFF 를 넘는 값도 거부 대상이다. `replace` 옵션은 오류 대신 U+FFFD(`�`)를 넣는다. 화면에 `�` 가 보인다면 그 시점에 이미 원본 바이트가 사라졌다는 뜻이다.

## 4. UTF-16 과 길이의 세 가지 의미

```text
코드 포인트 3 UTF-8 바이트 9 UTF-16 코드 유닛 4 CP949 바이트('가') 2
```

`"가😀é"` 의 길이를 네 가지로 센 결과다. UTF-16 은 U+FFFF 까지를 16 비트 하나로, 그 위를 서로게이트 쌍(16 비트 둘)으로 적는다. `😀` 은 `D83D DE00` 이다. Java 의 `String` 과 JavaScript 의 문자열은 UTF-16 코드 유닛의 배열이므로 `length` 가 4 를 돌려준다.

```java
String s = "가😀";
System.out.println("length=" + s.length() + " codePointCount=" + s.codePointCount(0, s.length())
	+ " charAt(1)=U+" + Integer.toHexString(s.charAt(1)).toUpperCase() + " isHighSurrogate=" + Character.isHighSurrogate(s.charAt(1)));
System.out.println("substring(0,2) UTF-8=" + hex(s.substring(0, 2).getBytes(StandardCharsets.UTF_8)));
System.out.println("'가나다'.getBytes(UTF_8).length=" + "가나다".getBytes(StandardCharsets.UTF_8).length
	+ " MS949=" + "가나다".getBytes("MS949").length);
```

```text
length=3 codePointCount=2 charAt(1)=U+D83D isHighSurrogate=true
substring(0,2) UTF-8=EA B0 80 3F
'가나다'.getBytes(UTF_8).length=9 MS949=6
```

`substring(0, 2)` 는 서로게이트 쌍의 가운데를 잘랐고, 짝 잃은 서로게이트는 UTF-8 로 적을 수 없어 `3F`(`?`)로 바뀌었다. "앞 N 글자만 표시" 를 `substring` 으로 구현하면 이모지에서 이렇게 깨진다. 길이 검증도 같은 문제를 가진다. 화면의 "10 자 이내" 는 글자 수이고 DB 컬럼의 길이는 정의에 따라 바이트 수다. Oracle 의 `VARCHAR2(10)` 은 기본 설정(`NLS_LENGTH_SEMANTICS=BYTE`)에서 10 바이트이므로, AL32UTF8 데이터베이스에서는 한글 세 글자(9 바이트)까지만 들어간다. 같은 컬럼이 KO16MSWIN949 데이터베이스에서는 다섯 글자를 받는다. 문자 집합을 바꾸는 이관에서 길이 초과 오류가 나는 원인이다.

## 5. 깨진 모양으로 원인을 역추적한다

```python
t = "한글 인코딩"
u8, c9 = t.encode("utf-8"), t.encode("cp949")
print("UTF-8 → CP949 로 읽음:", u8.decode("cp949", "replace"))
print("CP949 → UTF-8 로 읽음:", c9.decode("utf-8", "replace"))
print("UTF-8 → Latin-1 로 읽음:", repr(u8.decode("latin-1")))
print("UTF-8 → CP1252 로 읽음:", u8.decode("cp1252", "replace"))
```

```text
UTF-8 → CP949 로 읽음: �븳湲� �씤肄붾뵫
CP949 → UTF-8 로 읽음: �ѱ� ���ڵ�
UTF-8 → Latin-1 로 읽음: 'í\x95\x9cê¸\x80 ì\x9d¸ì½\x94ë\x94©'
UTF-8 → CP1252 로 읽음: í•œê¸€ ì�¸ì½”ë”©
```

| 화면에 보이는 모양 | 실제 바이트 | 잘못 적용된 인코딩 |
|---|---|---|
| 낯선 한자와 한글이 섞임(`湲`, `肄붾뵫`) | UTF-8 | CP949 |
| `�` 가 대부분이고 키릴·아랍 문자가 드문드문 | CP949 | UTF-8 |
| `í•œê¸€` 처럼 악센트 붙은 라틴 문자 | UTF-8 | CP1252 또는 Latin-1 |
| 글자마다 `?` 한 개 | (손실됨) | 대상 인코딩에 없는 글자를 인코딩 |

첫 번째가 Windows 에서 가장 흔하다. UTF-8 로 저장한 소스나 로그를 CP949 로 여는 경우다. UTF-8 한글 3 바이트가 CP949 의 2 바이트 단위로 다시 묶이면서 한자 영역과 확장 한글 영역의 글자가 나온다. 두 번째는 반대 방향으로, CP949 바이트 대부분이 UTF-8 로는 무효라 `�` 가 된다.

복구 가능성은 잘못 읽은 결과를 다시 바이트로 되돌릴 수 있는가에 달려 있다.

```text
Latin-1 왕복: 한글 인코딩
CP949 strict 디코딩 실패: illegal multibyte sequence 위치 0
'가나다' UTF-8 → CP949: 媛��굹�떎 / 왕복: 실패 illegal multibyte sequence
```

Latin-1 은 256 개 바이트 값 전부에 글자가 배정돼 있어 어떤 바이트열도 손실 없이 문자열이 된다. 그래서 `깨진문자열.encode("latin-1").decode("utf-8")` 로 원문이 돌아온다. CP949 로 잘못 읽은 경우는 다르다. 한글 한 글자가 3 바이트이므로 글자 수가 홀수이면 2 바이트씩 묶다가 짝이 맞지 않는 바이트가 생기고, 표에 없는 조합도 나온다. 그 자리가 `�` 로 치환되면 원래 바이트를 알 수 없다. `가나다`(9 바이트)가 `媛��굹�떎` 가 된 것이 그 예다. 치환 문자가 섞인 채로 DB 에 저장됐다면 그 값에서 원문을 복구할 수 없고 원본 파일을 다시 읽어야 한다.

## 6. EUC-KR 과 CP949 는 같은 것이 아니다

```text
현대 한글 음절 11172 / CP949 2바이트 11172 / EUC-KR 2바이트 2350
'똠' CP949: 8C 63 → 둘째 바이트 0x63 은 ASCII 'c' / EUC-KR: A4 D4 A4 A8 A4 C7 A4 B1
```

EUC-KR 의 한글은 KS X 1001 의 완성형 2,350 자다. CP949(Windows 코드 페이지 949)는 그 위에 나머지 8,822 자를 빈 영역에 추가해 현대 한글 11,172 자를 모두 담는다. 추가 영역은 둘째 바이트가 ASCII 영문자 범위까지 내려온다. Python 의 `euc-kr` 코덱은 표에 없는 `똠` 을 8 바이트의 자모 조합 표기로 내보냈고, Java 는 다르게 처리했다.

```text
'똠' EUC-KR=3F MS949=8C 63 x-windows-949=8C 63
EUC-KR canEncode('똠')=false MS949 canEncode('😀')=false
'가😀' MS949=B0 A1 3F
```

Java 의 `EUC-KR` 은 `똠` 을 `?` 로 바꾼다. 예외는 나지 않는다. 고객사 인터페이스 명세에 "EUC-KR" 이라고 적혀 있어도 실제 시스템이 Windows 계열이면 CP949 인 경우가 많으므로, 이름에 `똠`, `쌰` 같은 글자가 들어간 데이터로 확인해야 한다. Java 에서 CP949 에 해당하는 이름은 `MS949`(별칭 `x-windows-949`)다. 이모지처럼 CP949 에 없는 글자도 `getBytes` 에서 조용히 `?` 가 된다.

## 7. Java 의 디코딩은 기본적으로 오류를 숨긴다

```java
byte[] cp949 = "한글".getBytes("MS949");
String wrong = new String(cp949, StandardCharsets.UTF_8);
System.out.println("MS949 bytes=" + hex(cp949) + " → new String(UTF_8) U+FFFD 개수=" + wrong.chars().filter(c -> c == 0xFFFD).count());
System.out.println("되돌린 바이트=" + hex(wrong.getBytes(StandardCharsets.UTF_8)));
try {
	StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
		.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(cp949));
} catch (CharacterCodingException e) {
	System.out.println("REPORT 디코더: " + e.getClass().getSimpleName() + " " + e.getMessage());
}
```

```text
MS949 bytes=C7 D1 B1 DB → new String(UTF_8) U+FFFD 개수=2
되돌린 바이트=EF BF BD D1 B1 EF BF BD
REPORT 디코더: MalformedInputException Input length = 1
```

`new String(bytes, charset)`, `InputStreamReader`, `Files.readString` 가운데 앞의 둘은 잘못된 입력을 U+FFFD 로 바꾸고 계속 진행한다. 예외가 없으므로 로그에도 남지 않는다. 되돌린 바이트를 보면 원래의 `C7` 과 `DB` 가 `EF BF BD` 로 바뀌어 원본이 사라졌다. 외부에서 받은 파일을 검증하며 읽어야 한다면 `CharsetDecoder` 에 `REPORT` 를 지정해 예외로 받는다. `Files.readString` 과 `Files.readAllLines` 는 내부적으로 오류를 보고하는 디코더를 쓰므로 잘못된 바이트에서 `MalformedInputException` 을 던진다.

기본 인코딩은 JDK 18 의 JEP 400 에서 바뀌었다.

```text
defaultCharset=UTF-8 file.encoding=UTF-8 native.encoding=ANSI_X3.4-1968 stdout.encoding=UTF-8
```

JDK 18 부터 `Charset.defaultCharset()` 은 운영체제 로캘과 무관하게 UTF-8 이다. 그 전에는 한국어 Windows 에서 MS949 였다. JDK 17 이하에서 인코딩을 지정하지 않고 `FileReader` 로 읽던 코드는 JDK 를 올리면 같은 CP949 파일을 UTF-8 로 읽게 된다. `-Dfile.encoding=COMPAT` 로 이전 동작을 유지할 수 있다. 표준 출력은 별도다. `stdout.encoding` 은 콘솔의 인코딩을 따르고, 이 실행에서는 `-Dstdout.encoding=UTF-8` 을 지정했다. 지정하지 않았을 때는 `native.encoding` 인 ASCII 가 적용되어 한글이 전부 `?` 로 출력됐다. 프로그램 안의 문자열은 정상인데 콘솔에서만 깨지는 경우가 이 설정이다.

## 8. BOM

```python
print("UTF-8 BOM:", hx(codecs.BOM_UTF8), "UTF-16 LE:", hx(codecs.BOM_UTF16_LE), "BE:", hx(codecs.BOM_UTF16_BE))
raw = codecs.BOM_UTF8 + "id,name\n".encode()
print("utf-8 로 읽기:", repr(raw.decode("utf-8")), "/ utf-8-sig:", repr(raw.decode("utf-8-sig")))
print("첫 헤더 == 'id' ?", raw.decode("utf-8").split(",")[0] == "id")
```

```text
UTF-8 BOM: EF BB BF UTF-16 LE: FF FE BE: FE FF
utf-8 로 읽기: '﻿id,name\n' / utf-8-sig: 'id,name\n'
첫 헤더 == 'id' ? False
```

BOM 은 파일 맨 앞에 적는 U+FEFF 다. UTF-16 에서는 바이트 순서를 알려 주는 역할을 하고, UTF-8 에서는 순서가 하나뿐이라 "이 파일은 UTF-8" 이라는 표시로만 쓰인다. Excel 이 CSV 를 UTF-8 로 인식하게 하려고 붙이는 경우가 대표적이다. 문제는 읽는 쪽이다. Java 는 UTF-8 BOM 을 제거하지 않는다.

```text
header.length=3 equals("id")=false 첫 char=U+FEFF
BOM 제거 후 equals=true
UTF_16 로 쓴 파일=FE FF AC 00
```

`EF BB BF` 로 시작하는 CSV 를 `Files.readAllLines(file, UTF_8)` 로 읽으면 첫 헤더가 `"﻿id"` 가 되어 `equals("id")` 가 거짓이다. U+FEFF 는 화면에 보이지 않아서 로그로 찍어도 `id` 로만 보인다. 첫 컬럼만 매핑에 실패하는 CSV 업로드 오류의 원인이 대개 이것이다. 첫 줄의 맨 앞 U+FEFF 를 직접 제거해야 한다. 쓰는 쪽에서는 `StandardCharsets.UTF_16` 이 BOM(`FE FF`)을 자동으로 붙이고 `UTF_16LE`, `UTF_16BE` 는 붙이지 않는다. Windows PowerShell 5.1 은 `>` 와 `Out-File` 의 기본 인코딩이 UTF-16LE(BOM 포함)이고, PowerShell 7 은 BOM 없는 UTF-8 이다. 5.1 에서 리다이렉션으로 만든 파일을 다른 도구가 읽지 못할 때 확인할 부분이다.

## 9. 실무 판단

- 바이트와 문자열이 만나는 모든 지점(파일, 소켓, HTTP 본문, JDBC URL, 프로세스 표준 입출력)에서 인코딩을 명시한다. 기본값은 JDK 버전과 실행 환경에 따라 달라진다.
- 외부 입력은 `REPORT` 디코더로 읽어 잘못된 바이트를 예외로 받는다. `�` 가 저장된 뒤에는 복구할 수 없다.
- 깨진 글자는 5절의 표로 방향을 먼저 판정한다. 악센트 라틴 문자 형태는 대부분 복구할 수 있고, `�` 가 섞였으면 원본을 다시 받아야 한다.
- "EUC-KR" 명세는 KS X 1001 범위 밖의 한글로 실제 인코딩을 확인한다.
- 길이 제한은 글자 수, UTF-16 코드 유닛 수, 바이트 수 가운데 무엇인지 계층마다 정한다.
- CSV 를 읽을 때는 맨 앞 U+FEFF 를 제거한다.

검증 환경: Python 3.13.18 과 OpenJDK 21.0.13(Linux, `java -Dstdout.encoding=UTF-8 Enc.java`)에서 실행한 출력이다. Python 출력은 `enc.py` 한 파일의 결과에서 발췌했고 Java 코드는 `Enc.java` 의 `main` 에서 발췌했다. `hex` 는 `HexFormat` 으로 바이트를 16진수로 찍는 보조 메서드다. 4절의 Oracle 길이 의미론, 7절의 JDK 17 이하 기본 인코딩, 8절의 PowerShell 기본 인코딩은 아래 문서에 근거하며 이 환경에서 실행하지 않았다. `Files.readString`, `Files.readAllLines`, `InputStreamReader` 의 동작 차이는 MS949 바이트를 담은 파일로 따로 실행해 확인했다(앞의 둘은 `MalformedInputException`, 마지막은 U+FFFD 2 개).

## 참고

- RFC 3629, UTF-8, a transformation format of ISO 10646: https://www.rfc-editor.org/rfc/rfc3629
- The Unicode Standard, Chapter 3 Conformance (UTF-8 의 well-formed 바이트열 표, U+FFFD 치환): https://www.unicode.org/versions/latest/core-spec/chapter-3/
- JEP 400, UTF-8 by Default: https://openjdk.org/jeps/400
- Java SE 21 API, CharsetDecoder (CodingErrorAction): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/charset/CharsetDecoder.html
- Java SE 21 API, Files.readString (MalformedInputException): https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html
- Java SE 21, Supported Encodings (EUC-KR, MS949): https://docs.oracle.com/en/java/javase/21/intl/supported-encodings.html
- JDK-4508058, UTF-8 encoding does not recognize initial BOM: https://bugs.openjdk.org/browse/JDK-4508058
- Python 문서, codecs (utf-8-sig, 오류 처리기): https://docs.python.org/3/library/codecs.html
- Microsoft Learn, about_Character_Encoding (PowerShell 버전별 기본 인코딩): https://learn.microsoft.com/powershell/module/microsoft.powershell.core/about/about_character_encoding
- Oracle Database 19c Globalization Support Guide, Length Semantics: https://docs.oracle.com/en/database/oracle/oracle-database/19/nlspg/choosing-character-set.html
