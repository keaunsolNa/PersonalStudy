Notion 원본: https://app.notion.com/p/3d85a06fd6d38138b340c20db9046f31?pvs=204

# HTTP 캐시 의미론과 CDN 엣지 캐싱 및 stale-while-revalidate

> 2026-09-11 신규 주제 · 확장 대상: HTTP / 네트워크

## 학습 목표

- 캐시 계층별 역할과 freshness lifetime 계산 순서를 근거 RFC 조항과 함께 파악한다
- `Cache-Control` 디렉티브를 요청/응답 방향으로 구분해 정확히 선택한다
- `ETag`/`Vary`/조건부 요청으로 재검증 비용과 캐시 키 카디널리티를 제어한다
- stale-while-revalidate·stale-if-error·퍼지 전략을 Spring·CDN 운영에 적용한다

## 1. 캐시 계층과 freshness 모델

HTTP 캐시는 하나의 박스가 아니라 요청 경로 위에 겹걹이 놓인 여러 저장소다. 브라우저의 개인 캐시(private cache)는 한 사용자만 쓰므로 로그인 사용자 전용 응답도 담을 수 있다. 회사망의 포워드 프록시, 그리고 CDN 엣지는 여러 사용자가 공유하는 shared cache 라서, 한 사용자의 개인화 응답이 들어가면 즉시 정보 유출 사고가 된다. 오리진 앞단의 리버스 프록시(Nginx, Varnish)는 위치상 shared cache 이면서 동시에 오리진 보호 장치 역할을 한다. 같은 `Cache-Control` 이라도 어느 계층이 읽느냐에 따라 해석이 갈린다는 점이 캐싱 설계의 출발점이다.

현행 규범은 **RFC 9111(HTTP Caching, 2022)** 이며, 이전의 RFC 7234 를 폐기(obsolete)하고 대체했다. 일반 의미론과 상태 코드·헤더 필드 정의는 **RFC 9110(HTTP Semantics)** 에 있다. 이제 문서를 인용할 때 7234 를 들면 구세대 근거를 든 셈이니 9111 로 갱신해 두는 편이 좋다.

응답이 "신선한가"는 freshness lifetime 과 current_age 를 비교해 판정한다. freshness lifetime 을 정하는 우선순위는 공유 캐시 기준으로 `s-maxage` → `max-age` → `Expires` → heuristic(휴리스틱) 순이다. 개인 캐시는 `s-maxage` 를 무시하므로 `max-age` 부터 본다. heuristic 은 명시 만료가 전혀 없을 때만 쓰이며, 흔히 `Last-Modified` 와 현재 시각 차이의 10% 를 취한다. current_age 는 응답의 `Date` 와 수신 시각, 그리고 중간 캐시가 붙여 준 `Age` 를 조합해 계산한다. `Age` 는 "이 응답이 오리진에서 생성된 뒤 캐시 안에서 흘려보낸 초"라서, 엣지 히트 여부를 진단하는 가장 값싼 신호다.

```http
HTTP/1.1 200 OK
Date: Fri, 11 Sep 2026 03:00:12 GMT
Cache-Control: public, max-age=60, s-maxage=600
Age: 137
ETag: "v7-9c1f"
```

위 응답을 CDN 이 돌려줌다면 공유 캐시 기준 수명은 600초, 남은 신선 시간은 463초다. 같은 응답을 브라우저가 받으면 `max-age=60` 을 쓰는데 `Age: 137` 이므로 브라우저 입장에서는 이미 stale 이라 곷바로 재검증한다. 트레이드오프는 분명하다. `s-maxage` 를 키우면 오리진 오프로드가 올라가지만 퍼지 없이는 최대 10분 묵은 데이터가 노출된다. 반대로 수명을 줄이면 일관성은 좋아지고 오리진 QPS 와 비용이 선형으로 늘어난다.

## 2. `Cache-Control` 디렉티브 매트릭스

`Cache-Control` 은 요청에도 응답에도 붙지만 의미가 서로 다르다. 실무 사고의 상당수는 이 방향성을 뭉뛱그려 외운 데서 나온다. 예를 들어 `max-age=0` 은 응답에서는 "즉시 stale", 요청에서는 "0초보다 오래된 저장본은 주지 마라"라는 요구다. `only-if-cached` 는 요청 전용이며 응답에는 존재하지 않는다.

| 디렉티브 | 방향 | 의미 | 주 사용처 |
|---|---|---|---|
| `max-age=N` | 요청·응답 | 응답: N초간 신선 / 요청: N초 넘은 저장본 거부 | 공통 기본값 |
| `s-maxage=N` | 응답 | 공유 캐시 전용 수명, `max-age`·`Expires` 를 덮어쑸 | CDN 장기 TTL |
| `no-cache` | 요청·응답 | 저장은 허용, 재사용 전 오리진 재검증 필수 | 자주 바뀌는 HTML |
| `no-store` | 요청·응답 | 어떤 저장소에도 남기지 않음 | 결제·인증 토큰 응답 |
| `private` | 응답 | 공유 캐시 저장 금지, 브라우저 캐시는 허용 | 로그인 사용자 응답 |
| `public` | 응답 | 평소 저장 불가 조건에서도 저장 허용 신호 | 인증 뒤 공용 자산 |
| `must-revalidate` | 응답 | stale 상태면 재검증 성공 전까지 재사용 금지 | 금액·재고 표시 |
| `proxy-revalidate` | 응답 | `must-revalidate` 를 공유 캐시에만 적용 | 개인 캐시 완화 |
| `immutable` | 응답 | 신선한 동안 사용자 새로고침에도 재검증 생략 | 해시 파일명 자산 |
| `no-transform` | 요청·응답 | 중간 경유지의 본문 변형(이미지 재압축 등) 금지 | 원본 보존 필요 |
| `only-if-cached` | 요청 | 캐시에 없으면 504 반환, 네트워크 금지 | 오프라인 모드 |

`no-cache` 와 `no-store` 혼동은 양방향으로 사고를 만든다. 개인정보 조회 API 에 `no-cache` 만 걸어 두면 응답은 디스크 캐시에 남고, 공용 PC 에서 뒤로가기나 포렌식으로 내용이 되살아난다. 반대로 대용량 정적 이미지에 습관적으로 `no-store` 를 붙이면 매 요청이 오리진까지 내려가 CDN 비용과 지연이 동시에 폭증한다. 기억할 문장은 하나다. `no-cache` 는 "묻고 써라", `no-store` 는 "남기지 마라". 민감 응답의 안전한 조합은 아래처럼 저장 자체를 막는 쪽이다.

```http
Cache-Control: no-store, private
Pragma: no-cache
```

## 3. 조건부 요청과 재검증

stale 이 된 응답은 버리는 대신 검증기(validator)로 "아직 같은가"를 물어 304 로 되살리는 편이 싸다. 검증기는 두 가지다. `ETag` 는 표현(representation)의 불투명한 식별자이고, `Last-Modified` 는 HTTP-date 라서 해상도가 1초다. 1초 안에 두 번 바뀌는 리소스는 `Last-Modified` 만으로 변경을 구분할 수 없고, 이 때문에 RFC 9110 은 `Last-Modified` 를 약한 검증기로 취급한다. `W/` 접두사가 붙은 weak ETag 는 "의미는 같지만 바이트는 다를 수 있음"을 뜻해 바이트 단위 정확성이 필요한 Range 요청에는 쓸 수 없다.

```http
GET /api/v1/products/9271 HTTP/1.1
If-None-Match: "v7-9c1f"
If-Modified-Since: Fri, 11 Sep 2026 02:58:00 GMT

HTTP/1.1 304 Not Modified
ETag: "v7-9c1f"
Date: Fri, 11 Sep 2026 03:12:44 GMT
Cache-Control: public, max-age=60, s-maxage=600
```

둘 다 보내면 서버는 `If-None-Match` 를 우선 평가하고, ETag 가 있으면 `If-Modified-Since` 는 무시한다. 304 를 받은 캐시는 저장본의 헤더를 응답에 실려 온 값으로 갱신해야 한다. 특히 `Date`, `Cache-Control`, `Expires`, `ETag`, `Vary` 가 갱신 대상이며, 이 갱신 덕분에 freshness 시계가 다시 돌다. 304 에 `Cache-Control` 을 빼먹는 서버는 저장본의 낡은 수명을 그대로 두어 재검증 주기가 요동한다.

Spring 의 `ShallowEtagHeaderFilter` 는 컨트롤러가 만든 응답 본문 전체를 메모리에 버퍼링한 뒤 MD5 해시로 ETag 를 만든다. 즉 **컨트롤러·서비스·DB 조회는 이미 다 수행된 뒤**에 비교가 일어나므로, 절약되는 것은 네트워크 대역폭뿐이고 서버 CPU·DB 부하는 그대로다. 오히려 버퍼링 때문에 메모리 사용과 스트리밍 응답 호환성이 나빠진다. 연산까지 아낀려면 컨트롤러 진입 직후 버전 컬럼이나 `updatedAt` 으로 ETag 를 만들어 조기 반환하는 deep ETag 방식을 쓴다.

```java
@GetMapping("/api/v1/products/{id}")
public ResponseEntity<ProductResponse> get(@PathVariable long id, WebRequest request) {
	long version = productQueryService.findVersion(id); // 가벼운 단일 컬럼 조회
	String etag = "\"v" + version + "\"";
	if (request.checkNotModified(etag)) {
		return null; // 본문 생성 없이 304 반환
	}
	return ResponseEntity.ok()
		.eTag(etag)
		.cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
		.body(productQueryService.findDetail(id));
}
```

## 4. `Vary` 와 캐시 키

캐시 키는 기본적으로 메서드와 URL 이다. 하지만 같은 URL 이 요청 헤더에 따라 다른 표현을 내보낸다면 `Vary` 로 그 사실을 알려야 한다. `Vary: Accept-Encoding` 은 gzip/br/무압축 세 갈래 정도로만 갈리므로 정당하고 필수적이다. 이걸 빠뜨리면 br 만 아는 클라이언트가 gzip 본문을 받아 깨지는 고전적 사고가 난다.

반면 `Vary: User-Agent` 는 재앙이다. UA 문자열은 브라우저 버전·OS 빌드까지 섞여 사실상 무한에 가까운 값을 가지므로, 캐시 항목이 UA 종류만큼 쪠개지는 카디널리티 폭발이 일어난다. 히트율이 한 자릿수로 떨어지고 엣지 저장 공간은 중복본으로 가득 찬다. `Vary: Cookie` 는 더 나쁘다. 세션 ID·광고 식별자까지 포함되니 사용자 수만큼 사본이 생기고, 사실상 캐시를 끔 것과 같으면서 저장 비용만 든다.

| 패턴 | 키 분화 정도 | 실무 판단 |
|---|---|---|
| `Vary: Accept-Encoding` | 2~3배 | 압축 응답이면 필수 |
| `Vary: Accept-Language` | 지원 로케일 수만큼 | 로케일을 제한 목록으로 정규화 후 허용 |
| `Vary: Origin` | 허용 오리진 수만큼 | CORS 응답이면 필요, 화이트리스트로 제한 |
| `Vary: User-Agent` | 사실상 무한 | 금지. 디바이스 클래스 헤더로 정규화 |
| `Vary: Cookie` | 사용자 수만큼 | 금지. 개인화 구간은 `private` 또는 비캐시 |

CDN 은 `Vary` 외에 자체 캐시 키 정책을 제공한다. 쿼리스트링 정규화로 `utm_*` 같은 추적 파라미터를 키에서 제외하면 동일 콘텐츠가 수천 갈래로 쪠개지는 것을 막는다. 헤더 화이트리스트는 키에 들어갈 헤더를 명시적으로 한정해, 클라이언트가 임의 헤더를 던져 캐시를 오염시키거나 분열시키는 것을 차단한다. 사용자 그룹이 몇 개뿐이라면 로그인/비로그인, 국가, 디바이스 클래스처럼 세그먼트를 소수의 값으로 축약한 헤더를 엣지에서 계산해 키에 넣는 세그먼트 기반 캐싱이 좋다. 트레이드오프는 개인화 정밀도와 히트율의 교환이며, 보통 세그먼트 수를 10개 이하로 묶을 때 균형이 맞는다.

## 5. stale-while-revalidate 와 stale-if-error

**RFC 5861** 은 `Cache-Control` 확장 두 개를 정의한다. `stale-while-revalidate=N` 은 신선도가 끝난 뒤 N초 동안, 캐시가 저장본을 즉시 내주면서 **백그라운드로 재검증**해도 좋다는 허가다. `stale-if-error=N` 은 오리진이 5xx 나 연결 실패를 낼 때 N초까지 묵은 응답으로 버텨도 좋다는 허가다.

```http
Cache-Control: public, max-age=60, stale-while-revalidate=300, stale-if-error=86400
```

이 헤더의 타임라인을 구체적으로 따라가 보자. t=0 에 오리진에서 응답을 받아 엣지에 저장한다. t=59 요청은 신선 구간이므로 저장본을 그대로 주고 오리진 접촉은 없다. t=61 요청은 이미 stale 이지만 swr 창(60~360초) 안이므로, 캐시는 **기다리지 않고** 저장본을 반환하고 동시에 비동기 재검증을 띄운다. 이 사용자는 오리진 왕복 지연을 전혀 겪지 않는다. 재검증이 t=61.2 에 200 이나 304 로 끝나면 수명이 다시 60초로 리셋되어 t=121 까지 신선하다. 트래픽이 끊겼다가 t=400 에 요청이 오면 swr 창(360초)마저 지났으므로 캐시는 동기식으로 오리진에 다녀오고, 이 요청만 전체 지연을 부담한다. 만약 그 순간 오리진이 503 이라면 `stale-if-error=86400` 덕분에 하루치까지는 묵은 본문으로 사용자에게 정상 화면을 보여 줄 수 있다.

체감 지연이 사라지는 원리는 "만료 순간의 첫 요청자가 갱신 비용을 대신 지불한다"는 구조를 없앤 데 있다. 전통적 캐시에서는 TTL 만료 직후 도착한 불운한 사용자가 오리진 왕복을 통째로 떠안았고, 동시 도착 시에는 스팂피드로 번졌다. swr 은 그 비용을 백그라운드로 옮긴다. 다만 백그라운드 재검증이 요청 수만큼 발사되면 그 자체가 폭주가 되므로, CDN·프록시는 리소스 키 단위로 진행 중인 재검증을 하나만 허용하는 coalescing 을 함께 걸어야 한다. Nginx 는 `proxy_cache_use_stale updating` 과 `proxy_cache_lock` 조합이 같은 역할을 한다.

```nginx
proxy_cache_path /var/cache/nginx keys_zone=api:100m inactive=10m;

location /api/ {
    proxy_cache api;
    proxy_cache_valid 200 60s;
    proxy_cache_use_stale updating error timeout http_500 http_502 http_503;
    proxy_cache_background_update on;
    proxy_cache_lock on;
    proxy_cache_lock_timeout 5s;
    add_header X-Cache $upstream_cache_status;
}
```

지원 편차는 설계 시 반드시 감안한다. 주요 CDN(Fastly, Cloudflare, Akamai, CloudFront 등)은 swr/sie 를 널리 지원하지만 세부 동작과 최대 허용 창은 제품마다 다르다. 브라우저 쪽은 Chromium 계열이 swr 을 지원하는 반면 Safari·Firefox 지원은 제한적이라, 브라우저 캐시만 믿고 설계하면 안 된다. 모르는 확장 디렉티브는 무시되도록 규정돼 있으니, swr 이 없으면 그냥 `max-age` 만 적용되는 안전한 열화로 끝난다는 점이 그나마 다행이다.

## 6. 캐시 무효화 전략

무효화에는 두 갈래가 있다. TTL 만료를 기다리는 수동 방식은 구현이 없고 예측 가능하지만 최대 TTL 만큼 낡은 데이터를 감수한다. 능동 퍼지(purge/invalidate)는 즉시성이 좋은 대신 API 호출 비용과 전파 지연, 그리고 "무엇을 퍼지할지" 목록을 관리하는 복잡도가 붙는다. URL 단위 퍼지는 한 기사 변경이 목록·검색·RSS 등 수십 개 URL 에 영향을 줄 때 금방 한계에 부딪힌다.

그래서 surrogate key(cache tag) 방식이 표준에 가깝다. 응답에 `Surrogate-Key: product-9271 category-shoes` 같은 태그를 붙여 두고, 상품이 바뀌면 태그 하나로 관련 페이지 전부를 한 번에 무효화한다. 태그 설계는 도메인 이벤트와 1:1 로 맞추는 것이 핵심이며, 태그가 지나치게 굵으면 한 번의 변경이 캐시 전체를 날려 오리진 폭주를 부른다.

```http
HTTP/1.1 200 OK
Surrogate-Control: max-age=3600
Surrogate-Key: product-9271 category-shoes
Cache-Control: public, max-age=60, stale-while-revalidate=600
```

프런트 자산은 무효화 자체를 없애는 쪽이 정답이다. 빌드 산출물에 콘텐츠 해시를 박은 파일명(`app.9c1f3a.js`)을 쓰고 `max-age=31536000, immutable` 을 주면, 내용이 바뀌면 URL 이 바뀌므로 퍼지가 필요 없다. 반대로 이 파일들을 참조하는 HTML 은 짧은 TTL 이나 `no-cache` 로 두어 항상 최신 참조를 내려보낸다.

```http
# /static/app.9c1f3a.js
Cache-Control: public, max-age=31536000, immutable

# /index.html
Cache-Control: no-cache
```

이 조합을 어기면 배포 사고가 난다. 흔한 패턴은 HTML 에 `max-age=600` 을 걸어 둔 채 배포하는 경우다. 구 HTML 을 물고 있는 캐시는 이미 삭제된 구 자산 URL 을 참조하고, 오리진에서 이전 빌드를 지웠다면 사용자는 404 와 함께 깨진 화면을 본다. 예방책은 세 가지다. 배포 시 HTML 을 먼저가 아니라 **자산 업로드 후에** 교체하고, 구 빌드 자산을 최소 한두 릴리스 동안 남기며, HTML TTL 을 60초 이하로 유지하거나 `no-cache` 로 두는 것이다.

## 7. 오리진 보호와 캐시 사고

인기 키의 TTL 이 만료되는 순간 수천 요청이 동시에 오리진으로 쏟아지는 현상이 캐시 스팂피드(thundering herd)다. 대응은 request coalescing 이다. 같은 키에 대해 진행 중인 오리진 요청을 하나로 묶고 나머지는 그 결과를 기다리게 한다. 앞 절의 swr 과 결합하면 기다림조차 없앨 수 있다. 여기에 TTL 에 ±10% 지터를 주어 만료 시각이 한 점에 몰리지 않게 하면 주기적 동시 만료도 완화된다. 404·5xx 를 짧게(수초~수십초) 저장하는 negative caching 은 존재하지 않는 키를 무한 반복 조회하는 트래픽으로부터 오리진을 지킨다. 단, 배포 직후 아직 준비되지 않은 리소스의 404 가 오래 굳지 않도록 수명은 짧게 잡는다.

보안 사고는 캐시 키 설계에서 나온다. **캐시 포이즌닝**은 오리진이 `X-Forwarded-Host`, `X-Forwarded-Scheme` 처럼 신뢰할 수 없는 헤더를 응답 본문(절대 URL, 스크립트 src)에 반영하는데 그 헤더가 캐시 키에는 포함되지 않을 때 성립한다. 공격자가 한 번 조작 요청을 보내 오염된 응답을 엣지에 심으면, 이후 정상 사용자들이 공격자 도메인의 스크립트를 로드한다. 방어는 반영하지 않거나(권장) 키에 포함시키거나 둘 중 하나이며, 어중간한 상태가 취약점이다. **캐시 디셉션**은 반대 방향이다. `/mypage/profile.css` 처럼 존재하지 않는 확장자를 붙인 URL을 피해자가 열게 만들면, 오리진은 라우팅상 개인화 HTML 을 돌려주는데 엣지는 확장자만 보고 정적 자원으로 판단해 저장한다. 공격자는 같은 URL 로 타인의 개인정보를 읽는다. 확장자가 아니라 응답의 `Content-Type` 과 `Cache-Control` 을 기준으로 저장을 결정하도록 정책을 바꿔야 한다.

`private` 누락 사고는 가장 흔하고 가장 치명적이다. 사용자 이름이 박힌 응답에 `public, max-age=300` 이 붙으면 엣지가 그것을 저장하고 다음 사용자에게 그대로 내준다. RFC 9111 은 `Authorization` 헤더가 포함된 요청의 응답은 공유 캐시가 저장하지 못하도록 기본 금지하되, 응답에 `public`, `s-maxage`, `must-revalidate` 가 있으면 예외로 허용한다. 즉 `public` 을 습관적으로 붙이면 이 안전장치가 스스로 해제된다. 그리고 쿠키 기반 세션에는 이 규칙이 적용되지 않으므로, 쿠키로 인증하는 서비스는 전적으로 `private`/`no-store` 를 직접 붙여야 한다.

## 8. 실전 설정 — Spring 과 검증 절차

Spring 에서는 정적 리소스와 API 응답을 다른 정책으로 분리하는 것이 기본이다. 정적 리소스는 `WebMvcConfigurer` 에서 리소스 체인과 함께 장기 TTL 을 주고, API 는 핸들러마다 `CacheControl` 로 세밀하게 정한다.

```java
@Configuration
public class WebCacheConfig implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/static/**")
			.addResourceLocations("classpath:/static/")
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
			.resourceChain(true)
			.addResolver(new VersionResourceResolver().addContentVersionStrategy("/**"));
	}
}

@RestController
public class CatalogController {

	@GetMapping("/api/v1/catalog/banners") // 전 사용자 공통 → CDN 캐싱 대상
	public ResponseEntity<List<BannerResponse>> banners() {
		CacheControl cc = CacheControl.maxAge(Duration.ofSeconds(60))
			.cachePublic()
			.staleWhileRevalidate(Duration.ofMinutes(5))
			.staleIfError(Duration.ofHours(24));
		return ResponseEntity.ok().cacheControl(cc).body(bannerService.findAll());
	}

	@GetMapping("/api/v1/me/orders") // 개인화 → 공유 캐시 금지
	public ResponseEntity<List<OrderResponse>> myOrders() {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(orderService.findMine());
	}
}
```

API 를 CDN 에 태울지는 세 가지 질문으로 판단한다. 응답이 사용자와 무관하게 동일한가, 수십 초 낡아도 업무상 허용되는가, 요청량이 충분히 커서 히트가 실제로 발생하는가. 셋 다 예면 태울 가치가 있다. 하나라도 아니면 `private`/`no-store` 로 두고 애플리케이션 레벨 캐시(Redis)로 푸는 편이 안전하다. 특히 쓰기 직후 읽기(read-your-writes)가 필요한 화면은 엣지 캐싱과 궁합이 나쁜므로, 변경 API 응답에서 관련 태그를 즉시 퍼지하거나 아예 캐싱 대상에서 뻐다.

검증은 `curl` 한 줄이면 시작된다. 같은 URL 을 두 번 때려 `Age` 가 증가하고 `X-Cache` 가 MISS 에서 HIT 로 바뀌는지 본다.

```bash
curl -sI https://cdn.example.com/api/v1/catalog/banners \
  | grep -Ei '^(age|cache-control|etag|x-cache|vary)'

for i in 1 2 3; do
  curl -sI https://cdn.example.com/api/v1/catalog/banners \
    | awk 'tolower($1) ~ /^(age|x-cache):/ {print}'
  sleep 2
done

# 조건부 요청이 304 로 떨어지는지 확인
curl -sI -H 'If-None-Match: "v7-9c1f"' https://cdn.example.com/api/v1/catalog/banners | head -1
```

`Age` 가 항상 0 이고 `X-Cache: MISS` 만 나온다면 `Vary` 과다, 쿠키 동반, `private` 설정, 쿼리스트링 분화 중 하나를 의심한다. 운영 지표는 아래 표를 기준으로 본다.

| 지표 | 정의 | 해석과 목표 |
|---|---|---|
| 캐시 히트율 | 히트 요청 / 전체 요청 | 정적 자산 95% 이상, 캐시 가능 API 70~90% |
| 오리진 오프로드 | 1 - (오리진 전송 바이트 / 엣지 전송 바이트) | 바이트 기준 지표. 대용량 자산 효과 파악에 유리 |
| 오리진 QPS | 엣지에서 오리진으로 나간 초당 요청 | TTL 조정·coalescing 효과의 직접 증거 |
| stale 서빙 비율 | swr/sie 로 내보난 응답 비율 | 상시 높으면 TTL 이 트래픽 대비 짧다는 신호 |
| 평균 `Age` | 히트 응답의 `Age` 평균 | TTL 대비 너무 작으면 키가 과분화된 상태 |
| 엣지 p95 지연 | 엣지 응답 시간 95분위 | swr 도입 시 만료 구간 스파이크가 사라져야 정상 |

마지막 트레이드오프 정리는 이렇다. TTL 을 늘리면 비용과 지연이 좋아지고 일관성이 나빠진다. 퍼지를 도입하면 일관성이 회복되지만 배포·운영 복잡도와 퍼지 폭주 위험이 생긴다. swr/sie 는 지연과 가용성을 동시에 개선하지만 "사용자가 잠시 낡은 데이터를 본다"는 사실을 제품 차원에서 수용해야 쓸 수 있다. 어느 쪽이든 헤더를 정하기 전에 그 응답의 허용 낡음(staleness budget)을 숫자로 먼저 합의하는 것이 순서다.

## 참고

- RFC 9111 — HTTP Caching: https://datatracker.ietf.org/doc/html/rfc9111
- RFC 9110 — HTTP Semantics: https://datatracker.ietf.org/doc/html/rfc9110
- RFC 5861 — HTTP Cache-Control Extensions for Stale Content: https://datatracker.ietf.org/doc/html/rfc5861
- MDN — HTTP caching: https://developer.mozilla.org/en-US/docs/Web/HTTP/Guides/Caching
- MDN — Cache-Control header reference: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Cache-Control
- web.dev — Love your cache / HTTP caching best practices: https://web.dev/articles/love-your-cache
- Spring Framework — HTTP Caching (CacheControl, ShallowEtagHeaderFilter): https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-caching.html
