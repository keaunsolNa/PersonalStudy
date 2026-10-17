Notion 원본: https://app.notion.com/p/3fc5a06fd6d381e19f7bfc9cfa2739dd?pvs=204

# 일관된 해싱(Consistent Hashing)과 가상 노드, Rendezvous Hashing 및 Jump Consistent Hash 비교

> 2026-10-17 신규 주제 · 확장 대상: 자료구조&알고리즘

## 학습 목표

- 단순 모듈러 해싱이 노드 증설 시 키 대부분을 재배치하는 이유를 수치로 확인한다
- 해시 링과 가상 노드의 분산 균등도를 가상 노드 수별로 측정하고 비교한다
- Rendezvous Hashing과 Jump Consistent Hash를 직접 구현해 이동 비율과 조회 비용을 대조한다
- 캐시, 샤딩, 로드밸런서 상황별로 알고리즘을 고르는 기준을 세운다

## 1. 모듈러 해싱의 한계와 문제 정의

분산 캐시나 샤딩 환경에서 가장 먼저 떠올리는 방식은 hash(key) mod N 이다. 구현이 한 줄이고 조회가 O(1)이며 분산도 해시 함수 품질에 따라 고르다. 문제는 N이 바뀌는 순간에 드러난다. 노드가 10대에서 11대가 되면 대부분의 키에서 나머지 값이 달라지므로, 캐시라면 거의 전부가 미스가 되고 샤드라면 데이터 대부분을 옮겨야 한다. 이론적으로 N대에서 N+1대로 늘 때 같은 노드에 남는 키의 비율은 대략 1/(N+1)에 가깝다. 즉 이동 비율은 N/(N+1)로 수렴한다.

우리가 원하는 성질은 두 가지다. 첫째, 균등성(balance)으로 각 노드가 키를 거의 1/N씩 맡아야 한다. 둘째, 단조성(monotonicity)과 최소 이동으로 노드를 하나 추가할 때 이동하는 키는 새 노드가 가져가야 할 몫인 1/(N+1)만이어야 하고, 기존 노드끼리 키를 주고받는 일이 없어야 한다. 일관된 해싱(Consistent Hashing)은 1997년 Karger 등이 웹 캐시 용도로 제안한 이 성질의 첫 실용 해법이고, Rendezvous Hashing(1996, Thaler와 Ravishankar)과 Jump Consistent Hash(2014, Lamping과 Veach)는 같은 목표를 다른 구조로 달성한다.

아래 코드는 이후 모든 실험이 공유하는 기반이다. 해시는 MD5의 앞 8바이트를 64비트 정수로 쓴다. 암호학적 강도가 필요한 것이 아니라 분포 균일성이 필요하므로 어떤 좋은 해시여도 무방하다. 실험은 키 100,000개(user:0 ~ user:99999)와 노드 10대를 11대로 늘리는 시나리오로 고정했고, 모든 수치는 클라우드 환경에서 Python 3으로 실제 실행한 결과다.

```python
import hashlib, bisect, statistics

def h64(s):
    return int.from_bytes(hashlib.md5(s.encode()).digest()[:8], "big")

K = 100000
keys = [f"user:{i}" for i in range(K)]
nodes = [f"node{i}" for i in range(10)]
nodes2 = nodes + ["node10"]

def moved(a, b):
    return sum(1 for x, y in zip(a, b) if x != y) / len(a) * 100

a = [h64(k) % 10 for k in keys]
b = [h64(k) % 11 for k in keys]
print(f"modulo moved={moved(a, b):.2f}%")   # 실측 90.71%
```

실측 결과 모듈러 방식의 이동 비율은 90.71%였다. 이상적인 최소 이동량 1/11 = 9.09%의 거의 10배에 해당한다. 이 비용이 허용되는 경우는 노드 수가 사실상 고정인 시스템뿐이며, 오토스케일링이나 장애 복구가 일상인 환경에서는 캐시 스탬피드(일제 미스로 원본 DB에 부하가 몰리는 현상)를 유발한다. trade-off 관점에서 모듈러는 단순함과 속도를 얻는 대신 탄력성을 완전히 포기한 선택이다.

## 2. 해시 링: Consistent Hashing의 기본 구조

일관된 해싱은 해시 공간 전체를 원형(링)으로 본다. 0부터 2의 64제곱 - 1까지의 정수 공간을 이어 붙여 원으로 만들고, 노드를 해시해 링 위의 한 점에 올린다. 키도 같은 해시 함수로 링 위의 점이 되며, 키에서 시계 방향으로 가장 먼저 만나는 노드가 담당자가 된다. 구현은 정렬된 노드 해시 배열에 이진 탐색(bisect)을 적용하면 되고, 배열 끝을 넘어가면 첫 원소로 되돌아간다(모듈러 연산).

이 구조에서 노드가 추가되면 새 노드의 점 바로 앞(반시계 방향) 구간의 키만 새 노드로 넘어간다. 다른 노드 사이에서 키가 이동하는 일은 없다. 노드가 제거되면 그 노드가 맡던 구간의 키가 시계 방향의 다음 노드로 넘어간다. 따라서 평균 이동량이 K/N으로 억제된다. 조회 비용은 노드 점의 개수 M에 대한 이진 탐색이므로 O(log M)이고, 메모리는 점의 개수에 비례한다.

```python
class Ring:
    def __init__(self, nodes, vnodes=1):
        self.points = sorted(
            (h64(f"{n}#{i}"), n) for n in nodes for i in range(vnodes)
        )
        self.keys = [p[0] for p in self.points]

    def get(self, key):
        i = bisect.bisect(self.keys, h64(key)) % len(self.keys)
        return self.points[i][1]

r = Ring(nodes, vnodes=1)
print(r.get("user:42"))
```

그러나 노드당 점이 하나뿐이면 구간 길이가 매우 불균등하다. 무작위로 점 N개를 원에 뿌리면 구간 길이의 분포는 지수분포에 가까워서 가장 긴 구간이 평균의 O(log N)배까지 커진다. 다음 절의 실측에서 vnodes=1일 때 표준편차가 평균의 102.73%에 달하고 가장 적게 받은 노드는 평균의 0.01배(사실상 비어 있음), 가장 많이 받은 노드는 3.38배를 받았다. 일관된 해싱의 최소 이동 성질은 확보했지만 균등성은 거의 보장되지 않는다는 뜻이다. 이 약점을 메우는 것이 가상 노드다.

## 3. 가상 노드(Virtual Node)와 분산 균등도 실측

가상 노드는 물리 노드 하나를 링 위의 여러 점으로 표현하는 기법이다. 위 코드의 vnodes 인자가 그것이며, node3#0, node3#1 처럼 접미사를 붙여 서로 다른 점을 만든다. 점의 개수가 늘수록 대수의 법칙에 의해 각 물리 노드가 차지하는 총 구간 길이가 평균에 수렴한다. 이론적으로 노드당 점이 v개일 때 상대 표준편차는 대략 1/sqrt(v)에 비례해 줄어든다. 즉 오차를 절반으로 줄이려면 점을 4배로 늘려야 한다.

```python
def stats(assign, names):
    c = {n: 0 for n in names}
    for a in assign:
        c[a] += 1
    v = list(c.values()); m = statistics.mean(v)
    return statistics.pstdev(v) / m * 100, max(v) / m, min(v) / m

for vn in [1, 10, 100, 1000]:
    r1, r2 = Ring(nodes, vn), Ring(nodes2, vn)
    a = [r1.get(k) for k in keys]
    b = [r2.get(k) for k in keys]
    cv, mx, mn = stats(a, nodes)
    print(vn, f"cv={cv:.2f}% max={mx:.2f} min={mn:.2f} moved={moved(a,b):.2f}%")
```

| vnodes/노드 | 표준편차/평균 | 최대/평균 | 최소/평균 | 10→11대 이동 비율 |
|---|---|---|---|---|
| 1 | 102.73% | 3.38 | 0.01 | 22.26% |
| 10 | 32.33% | 1.43 | 0.53 | 7.77% |
| 100 | 9.11% | 1.17 | 0.85 | 9.07% |
| 1000 | 3.29% | 1.03 | 0.92 | 9.00% |

표준편차는 이론대로 1/sqrt(v)를 따른다(10개에서 32%, 100개에서 9%, 1000개에서 3.3%). 이동 비율은 vnodes가 적을수록 이상값 9.09%에서 크게 벗어난다. vnodes=1에서 22.26%가 이동한 것은 새 노드가 운 좋게 큰 구간을 차지했기 때문이고, 반대로 vnodes=10의 7.77%는 새 노드가 평균보다 작은 몫을 받은 경우다. 이동량이 작다고 항상 좋은 것은 아니며, 새 노드의 부하가 적다는 뜻이기도 하다. 100개 이상에서야 이동 비율이 이론값에 근접한다.

가상 노드의 비용은 메모리와 조회 시간, 그리고 멤버십 변경 비용이다. 100노드 x 100 vnodes면 점이 10,000개이고 정렬 배열과 이진 탐색으로 충분하지만, 1000 vnodes는 노드 추가 때마다 수천 점을 재정렬해야 한다. 반면 이점도 있다. 노드마다 vnodes 수를 다르게 주면 서버 용량 차이를 가중치로 반영할 수 있고, 장애 노드의 부하가 다음 한 노드가 아니라 여러 노드로 흩어진다. Dynamo 논문은 이 특성을 이질적 서버 성능 대응과 장애 시 부하 분산의 이유로 들며 가상 노드를 채택했고, Cassandra도 초기에는 노드당 256 토큰을 기본값으로 썼으나 최근 버전은 노드당 토큰 수를 줄이고 할당 알고리즘으로 균형을 맞추는 방향으로 바뀌었다.

## 4. 노드 제거와 장애 시 동작

노드 제거 시의 성질은 추가 시와 대칭이다. 링에서 한 노드의 점들을 빼면, 그 노드가 맡던 키만 각 점의 시계 방향 이웃으로 넘어간다. 아래 실험은 vnodes=100인 10노드 링에서 node3을 제거했다. 이동한 키는 전체의 8.82%였고, 이동한 키가 전부 node3 소유였음을 검증했다. 다른 노드 사이의 불필요한 이동은 정확히 0이다.

```python
rem = [n for n in nodes if n != "node3"]
r1, r2 = Ring(nodes, 100), Ring(rem, 100)
a = [r1.get(k) for k in keys]
b = [r2.get(k) for k in keys]
print(moved(a, b))                                    # 8.82
print(all(x == "node3" for x, y in zip(a, b) if x != y))  # True
```

운영에서 중요한 점은 복제다. Dynamo 방식은 키를 담당 노드 한 곳이 아니라 링에서 시계 방향으로 이어지는 N개의 서로 다른 물리 노드(preference list)에 복제한다. 이때 가상 노드 때문에 연속된 점이 같은 물리 노드에 속하는 경우를 건너뛰어야 서로 다른 물리 노드 N개를 확보할 수 있다. 또한 장애를 일시적 이탈과 영구 제거로 구분해야 한다. 일시 장애에서 링 구성을 바꿔 버리면 복구 후 다시 데이터를 되돌려야 하므로, Dynamo는 hinted handoff로 일시 장애를 흡수하고 멤버십 변경은 명시적 동작으로 분리한다. trade-off는 분명하다. 링 구성을 자주 바꾸면 이동 비용이 반복되고, 바꾸지 않으면 죽은 노드로 요청이 계속 향한다. 헬스 체크 주기와 재시도 정책이 해시 알고리즘만큼 중요한 이유다.

## 5. Rendezvous Hashing (Highest Random Weight)

Rendezvous Hashing은 링을 쓰지 않는다. 키마다 모든 노드에 대해 점수 hash(node, key)를 계산하고 점수가 가장 큰 노드를 담당자로 고른다. 노드 집합이 바뀌어도 각 키의 점수는 변하지 않으므로, 노드 추가 시 새 노드가 어떤 키에서 최고점을 받으면 그 키만 이동하고 나머지는 그대로다. 제거 시에는 그 노드가 1등이던 키만 2등 노드로 넘어간다. 별도의 자료구조도, vnodes도 필요 없으며 노드 목록만 있으면 된다. 해시가 균등하다면 분산도 이론적으로 균등하다.

```python
def rendezvous(nodes, key):
    return max(nodes, key=lambda n: h64(f"{n}|{key}"))

a = [rendezvous(nodes, k) for k in keys]
b = [rendezvous(nodes2, k) for k in keys]
cv, mx, mn = stats(a, nodes)
print(f"cv={cv:.2f}% max={mx:.2f} min={mn:.2f} moved={moved(a,b):.2f}%")
```

실측 결과 표준편차는 평균의 1.20%, 최대/최소는 1.02/0.98, 이동 비율은 9.20%로 이상값 9.09%에 매우 가깝다. 가상 노드 1000개짜리 링(표준편차 3.29%)보다도 균등하다. 노드 제거(node3) 시 이동은 10.01%로, 해당 노드가 맡던 몫(약 10%)과 일치한다.

단점은 조회 비용이 노드 수 N에 대해 O(N)이라는 점이다. 노드 100대 기준 조회 1회에 해시를 100번 계산하므로 실측으로 약 54.6 마이크로초가 걸렸다(링 0.83, Jump 1.78 마이크로초 대비). 노드가 수십 대 이하이거나 조회가 병목이 아닌 컨트롤 플레인, 또는 가중치 기반 선택(점수에 가중치를 곱하거나 로그 변환)이 필요한 경우에 어울린다. 상위 k개 노드를 점수 순으로 뽑으면 복제 대상 선정도 자연스럽다. 계층(트리) 구조로 묶어 조회를 O(log N)으로 줄이는 변형도 있으나 구현이 복잡해진다.

## 6. Jump Consistent Hash

Jump Consistent Hash는 Google의 Lamping과 Veach가 2014년 발표한 알고리즘(arXiv 1406.2294)으로, 키와 버킷 수만 받아 0부터 n-1 사이의 버킷 번호를 계산한다. 메모리를 전혀 쓰지 않고 코드는 몇 줄이다. 핵심 아이디어는 "버킷이 j개에서 j+1개로 늘 때 키가 새 버킷으로 이동할 확률은 1/(j+1)이어야 한다"는 조건을 의사난수열로 시뮬레이션하는 것이다. 키를 시드로 한 선형 합동 생성기를 돌리며, 버킷 번호가 점프하는 지점만 건너뛰어 계산하므로 기대 반복 횟수는 ln(n)이다.

```python
def jump(key, n):
    b, j = -1, 0
    key &= 0xFFFFFFFFFFFFFFFF
    while j < n:
        b = j
        key = (key * 2862933555777941757 + 1) & 0xFFFFFFFFFFFFFFFF
        j = int((b + 1) * (float(1 << 31) / float((key >> 33) + 1)))
    return b

a = [jump(h64(k), 10) for k in keys]
b = [jump(h64(k), 11) for k in keys]
print(f"moved={moved(a, b):.2f}%")   # 실측 9.25%
```

실측 결과는 표준편차 1.21%, 최대/최소 1.02/0.98, 이동 비율 9.25%로 Rendezvous와 거의 동일한 품질이다. 조회 시간은 노드 100대 기준 약 1.78 마이크로초(Python 구현 기준)로, 해시 계산을 포함해도 Rendezvous보다 30배 가량 빠르다. 다만 이 속도는 순수 Python에서의 값이며 C, Go, Java 같은 컴파일 언어에서는 수십 나노초 수준으로 내려간다.

제약은 명확하다. 버킷에 이름이 없고 번호만 있으며, 번호 순서대로 끝에서만 늘리고 줄일 수 있다. 중간 버킷(예: 3번)이 죽었다고 그것만 제거하는 연산이 불가능하다. 따라서 임의 노드의 장애를 다루는 캐시 클러스터에는 맞지 않고, 샤드 번호가 논리적으로 정해지며 샤드 수가 단조 증가하는 데이터 스토어(예: 파티션 수 확장)에 적합하다. 장애 노드는 별도의 매핑 계층에서 번호와 실제 서버를 연결해 처리하는 식으로 우회한다. 또한 기대 반복 횟수가 ln(n)이므로 n이 아주 커져도 느려지지 않지만, 가중치는 지원하지 않는다.

## 7. 세 알고리즘의 종합 비교

앞선 실험 수치를 한곳에 모으면 다음과 같다. 이동 비율의 이상값은 1/11 = 9.09%이며, 균등도는 10노드 100,000키 기준이다.

| 항목 | 모듈러 | 링 (vnodes 100) | 링 (vnodes 1000) | Rendezvous | Jump Hash |
|---|---|---|---|---|---|
| 10→11대 이동 비율 | 90.71% | 9.07% | 9.00% | 9.20% | 9.25% |
| 표준편차/평균 | 해시 품질에 의존 | 9.11% | 3.29% | 1.20% | 1.21% |
| 조회 시간 복잡도 | O(1) | O(log M) | O(log M) | O(N) | 기대 O(ln N) |
| 조회 실측 (100노드, Python) | 해당 없음 | 0.83 마이크로초 | 미측정 | 54.59 마이크로초 | 1.78 마이크로초 |
| 메모리 | 없음 | 노드 x vnodes | 노드 x vnodes | 노드 목록 | 없음 |
| 임의 노드 제거 | 불가 | 가능 | 가능 | 가능 | 불가 (끝에서만) |
| 가중치 | 어려움 | vnodes 수 조절 | vnodes 수 조절 | 점수 가중 | 미지원 |

표에서 읽어야 할 핵심은 세 가지다. 첫째, 링은 균등성을 vnodes 수라는 비용으로 구매한다. 둘째, Rendezvous는 균등도와 유연성이 가장 좋고 조회만 비싸다. 셋째, Jump는 가장 빠르고 가볍지만 노드 식별과 임의 제거를 포기한다. 링의 조회 시간 0.83 마이크로초는 이진 탐색이 해시 계산보다 싸다는 의미이며, 반복 조회 시 Rendezvous와의 격차는 노드 수에 정비례해 벌어진다. 이 측정은 단일 프로세스의 Python 값이므로 절대값보다 상대 비율을 참고해야 한다.

## 8. 실무 선택 기준과 확장 기법

선택은 노드 집합의 변동 방식에서 출발한다. 노드가 임의로 들고 나고 수가 수백 이상이며 조회가 핫패스라면 vnodes를 충분히 둔 링이 표준이다. memcached 클라이언트 라이브러리의 ketama 방식이 대표적이며 서버당 약 160개 점을 쓴다. 노드가 수십 대 이하이고 균등도가 중요하거나 가중치와 상위 k개 선택이 필요하면 Rendezvous가 코드도 단순하고 안전하다. 파티션이 번호로 관리되고 늘리기만 하는 스토리지라면 Jump가 가장 경제적이다.

균등성만으로 부족한 경우도 있다. 특정 핫 키가 있거나 노드 용량이 한정된 로드밸런서에서는 해시의 확률적 균등이 곧 부하 균등이 아니다. Mirrokni, Thorup, Zadimoghaddam의 Consistent Hashing with Bounded Loads(arXiv 1608.01350)는 각 노드의 용량을 평균 부하의 (1+e)배로 제한하고, 가득 찬 노드를 만나면 링에서 다음 노드로 넘기는 방식으로 최대 부하를 보장한다. Google의 Maglev는 해시 링 대신 조회 테이블을 만들어 O(1) 조회와 균등한 분산을 얻는 대신 멤버십 변경 시 약간의 추가 이동을 허용하는 다른 절충을 택했다. 즉 일관된 해싱 계열은 최소 이동, 균등성, 조회 속도, 메모리 사이에서 어디에 무게를 두느냐의 선택지 모음이다.

구현 시 자주 놓치는 함정을 정리한다. 해시 함수가 편향되면 vnodes를 아무리 늘려도 소용없으므로 xxHash, MurmurHash3, SipHash 같은 검증된 해시를 써야 한다. 노드 이름 해시의 입력 형식(node#i)이 클라이언트마다 다르면 서로 다른 매핑이 만들어지므로, 여러 클라이언트가 같은 클러스터를 보는 환경에서는 알고리즘, 해시, vnodes 수, 이름 규칙을 모두 계약으로 고정해야 한다. 마지막으로 이동 최소화는 데이터 이동 자체를 자동으로 해결해 주지 않는다. 캐시라면 미스로 끝나지만 샤딩 저장소라면 이동 대상 키를 식별해 실제로 복사하고 이중 읽기 기간을 두는 마이그레이션 절차가 필요하다.

## 참고

- Karger et al., Consistent Hashing and Random Trees (STOC 1997): https://dl.acm.org/doi/10.1145/258533.258660
- Lamping, Veach, A Fast, Minimal Memory, Consistent Hash Algorithm (Jump Consistent Hash, 2014): https://arxiv.org/abs/1406.2294
- DeCandia et al., Dynamo: Amazon's Highly Available Key-value Store (SOSP 2007): https://www.allthingsdistributed.com/files/amazon-dynamo-sosp2007.pdf
- Thaler, Ravishankar, A Name-Based Mapping Scheme for Rendezvous (Highest Random Weight, 1996): https://www.eecs.umich.edu/techreports/cse/96/CSE-TR-316-96.pdf
- Mirrokni, Thorup, Zadimoghaddam, Consistent Hashing with Bounded Loads (2016): https://arxiv.org/abs/1608.01350
- Eisenbud et al., Maglev: A Fast and Reliable Software Network Load Balancer (NSDI 2016): https://research.google/pubs/maglev-a-fast-and-reliable-software-network-load-balancer/
