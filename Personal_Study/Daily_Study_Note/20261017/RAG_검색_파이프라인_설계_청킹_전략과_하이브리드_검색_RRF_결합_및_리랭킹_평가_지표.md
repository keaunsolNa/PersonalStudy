Notion 원본: https://app.notion.com/p/3fc5a06fd6d381cdba28f858112bd89e?pvs=204

# RAG 검색 파이프라인 설계: 청킹 전략과 하이브리드 검색(BM25+벡터), RRF 결합 및 리랭킹·평가 지표

> 2026-10-17 신규 주제 · 확장 대상: AI_Multi-Agent_서비스_실전프로젝트

## 학습 목표

- 문서 특성에 맞는 청킹 크기와 오버랩을 정하고 청킹 함수를 구현한다
- BM25와 밀집 벡터 검색을 병렬로 돌린 뒤 RRF로 결합하는 파이프라인을 설계한다
- 크로스 인코더 리랭커를 2단계 검색에 배치하고 지연·비용 trade-off를 판단한다
- recall@k, MRR, nDCG@k를 직접 계산해 검색 변경의 효과를 정량 비교한다

## 1. RAG에서 검색 단계가 품질의 상한을 정한다

RAG(Retrieval-Augmented Generation)는 질문과 관련된 문서 조각을 먼저 찾아 프롬프트에 넣고, 생성 모델이 그 근거 위에서 답하게 하는 구조다. 생성 모델이 아무리 강력해도 검색이 정답 근거를 가져오지 못하면 환각을 막을 수 없고, 반대로 근거가 올바르게 들어오면 비교적 작은 모델로도 안정적인 답을 얻는다. 그래서 실무에서는 프롬프트를 만지기 전에 검색 파이프라인의 각 단계가 어디서 정답을 놓치는지부터 계측한다.

전체 흐름은 크게 인덱싱 시점과 질의 시점으로 나뉜다. 인덱싱 시점에는 원문을 청크로 쪼개고, 각 청크를 역색인(BM25용)과 임베딩 벡터(밀집 검색용)로 동시에 저장한다. 질의 시점에는 두 검색기를 병렬 호출해 각각 상위 후보를 뽑고, RRF로 순위를 합친 뒤, 상위 수십 개만 리랭커로 재정렬해 최종 3~8개를 LLM에 전달한다. Multi-Agent 서비스에서는 이 검색 모듈을 여러 에이전트가 도구로 공유하므로, 파이프라인을 하나의 함수 계약(질의 입력, 순위 리스트 출력)으로 고정해 두면 에이전트별로 검색 품질이 달라지는 문제를 줄일 수 있다.

| 단계 | 입력 | 출력 | 주된 실패 양상 |
|---|---|---|---|
| 청킹 | 원문 문서 | 청크 리스트 | 정답 문장이 두 청크로 쪼개짐 |
| 1차 검색(BM25) | 질의 토큰 | 어휘 일치 순위 | 동의어·패러프레이즈 누락 |
| 1차 검색(벡터) | 질의 임베딩 | 의미 유사 순위 | 고유명사·코드·숫자 누락 |
| 결합(RRF) | 순위 리스트들 | 단일 순위 | k 선택, 후보 깊이 부족 |
| 리랭킹 | 질의+후보 쌍 | 재정렬 순위 | 지연 증가, 후보 밖 정답은 복구 불가 |

이 표에서 보듯 각 단계는 서로 다른 종류의 실수를 하므로, 단계를 쌓는 이유는 실수의 종류를 분산시키기 위해서다. 다만 단계가 늘수록 지연과 운영 복잡도가 커지므로 평가 지표로 이득이 확인된 단계만 남기는 것이 원칙이다.

## 2. 청킹 전략: 크기, 오버랩, 경계

청킹은 검색 단위를 결정하는 작업이다. 청크가 너무 크면 한 청크에 여러 주제가 섞여 임베딩이 희석되고 LLM 컨텍스트도 낭비된다. 너무 작으면 문맥이 끊겨 대명사나 생략된 주어가 무엇을 가리키는지 알 수 없게 된다. 고정 길이 분할은 구현이 단순하고 예측 가능하지만 문장 중간에서 잘릴 수 있어 오버랩으로 보완한다. 문장·문단 경계 분할은 의미 단위를 보존하지만 청크 길이가 들쭉날쭉해진다. 그 외에 마크다운 헤딩 같은 문서 구조를 따르는 분할, 임베딩 유사도 변화로 경계를 찾는 시맨틱 분할이 있는데, 후자는 인덱싱 비용이 늘어 효과가 데이터에 따라 갈린다.

아래는 고정 길이(문자 기준, 오버랩 포함)와 문장 경계 기반 분할을 구현한 코드다. toy 문서로 실행해 얻은 결과를 주석으로 남겼다.

```python
import re

def fixed_chunks(text, size=40, overlap=10):
    step = size - overlap
    return [text[i:i+size] for i in range(0, max(len(text)-overlap, 1), step)]

def sentence_chunks(text, max_chars=60):
    sents = re.split(r'(?<=[.!?])\s+', text.strip())
    chunks, cur = [], ""
    for s in sents:
        if cur and len(cur) + len(s) + 1 > max_chars:
            chunks.append(cur); cur = s
        else:
            cur = (cur + " " + s).strip()
    if cur: chunks.append(cur)
    return chunks

doc = ("RRF merges ranked lists. It uses only ranks. BM25 scores lexical overlap. "
       "Dense vectors capture meaning. Rerankers rescore the top candidates.")
fc = fixed_chunks(doc, 40, 10)
sc = sentence_chunks(doc, 60)
print(len(fc), fc[:2])   # 5 ['RRF merges ranked lists. It uses only ra', 'es only ranks. BM25 scores lexical overl']
print(len(sc))            # 3
for c in sc: print(" -", c)
# - RRF merges ranked lists. It uses only ranks.
# - BM25 scores lexical overlap. Dense vectors capture meaning.
# - Rerankers rescore the top candidates.
```

실행 결과 고정 길이 방식은 5개 청크를 만들었고 첫 청크가 "ra"에서 끊겨 단어가 잘렸다. 문장 경계 방식은 3개로 줄었고 모든 청크가 완결된 문장으로 끝난다. 이 차이는 작지만 실제 코퍼스에서는 질의가 걸리는 청크의 품질로 이어진다. 한국어는 형태소 단위로 의미가 쪼개지고 문장 부호가 영어보다 덜 일관적이라, 종결어미나 줄바꿈 기준 분리를 추가하고 크기는 문자 수보다 토큰 수로 재는 편이 안전하다. 실무 출발점으로는 200~500 토큰에 10~20% 오버랩이 흔히 쓰이지만 이는 경험칙이므로 반드시 자체 평가셋으로 검증한다.

청킹의 trade-off는 오버랩 비율에서 가장 선명하다. 오버랩을 키우면 경계에서 잘린 정답을 건질 확률이 오르지만 인덱스 크기와 중복 후보가 늘어나, 검색 결과 상위가 사실상 같은 문단의 변형들로 채워질 수 있다. 이 경우 결과 단계에서 근접 청크를 병합하거나 중복을 제거하는 후처리가 필요하다.

## 3. BM25: 어휘 일치 기반 희소 검색

BM25는 질의 단어가 문서에 몇 번, 얼마나 희귀하게 나타나는지로 점수를 매기는 확률적 랭킹 함수다. 핵심 요소는 세 가지다. 희귀한 단어일수록 가중치가 큰 IDF, 단어 빈도가 늘어도 점수 증가가 포화되도록 하는 k1, 긴 문서에 불이익을 주는 길이 정규화 b다. 흔히 쓰는 기본값은 k1=1.2, b=0.75이다. 신경망 임베딩과 달리 학습이 필요 없고, 제품 코드, 에러 메시지, 약어처럼 임베딩이 일반화하기 어려운 정확한 문자열 질의에 강하다.

```python
import math, re
from collections import Counter
docs = {
 "d1": "bm25 ranks documents by term frequency and inverse document frequency",
 "d2": "dense retrieval encodes text into vectors for semantic search",
 "d3": "reciprocal rank fusion combines bm25 and dense retrieval rankings",
 "d4": "chunking splits long documents into passages with overlap",
}
tok = lambda s: re.findall(r"\w+", s.lower())
T = {d: tok(t) for d, t in docs.items()}
N = len(T); avgdl = sum(map(len, T.values()))/N
df = Counter(w for t in T.values() for w in set(t))
def bm25(q, k1=1.2, b=0.75):
    out = {}
    for d, t in T.items():
        tf = Counter(t); s = 0.0
        for w in tok(q):
            if w not in tf: continue
            idf = math.log(1 + (N-df[w]+0.5)/(df[w]+0.5))
            s += idf*tf[w]*(k1+1)/(tf[w]+k1*(1-b+b*len(t)/avgdl))
        out[d] = round(s, 4)
    return sorted(out.items(), key=lambda x: -x[1])
print(bm25("bm25 dense retrieval fusion"))
# [('d3', 3.2834), ('d2', 1.3863), ('d1', 0.663), ('d4', 0.0)]
```

질의의 네 단어를 모두 포함한 d3이 3.2834로 1위가 되고, 일부만 겹치는 d2와 d1이 뒤를 잇고, 아무것도 겹치지 않는 d4는 0점이다. 이 예시는 BM25의 한계도 보여 준다. 질의가 "semantic search"로 바뀌어도 d3은 0점이지만 사람이 보면 관련이 있다. 한국어에서는 조사와 어미 변형 때문에 토큰화(형태소 분석기 또는 n-gram)가 성능을 좌우한다. 어떤 방식이든 인덱싱과 질의에 같은 토크나이저를 써야 한다는 점이 가장 흔한 실수 지점이다.

## 4. 밀집 벡터 검색과 하이브리드가 필요한 이유

밀집 검색은 질의와 청크를 같은 임베딩 공간의 벡터로 바꾸고 코사인 유사도나 내적으로 가까운 청크를 찾는다. "환불 방법"과 "결제 취소 절차"처럼 어휘가 달라도 의미가 같으면 찾아내므로 BM25의 약점을 메운다. 대신 임베딩 모델이 학습 때 보지 못한 사내 용어, 버전 번호, 식별자에는 약하고, 대규모 인덱스에서는 정확 탐색 대신 HNSW 같은 근사 최근접 탐색을 쓰므로 재현율과 속도 사이의 파라미터 튜닝이 따른다.

두 방식은 틀리는 방향이 서로 달라서 합칠 가치가 있다. BM25가 놓친 의미 유사 문서를 벡터가 건지고, 벡터가 놓친 정확 일치 문서를 BM25가 건진다. 문제는 두 점수의 척도가 다르다는 것이다. BM25 점수는 상한이 없고 질의와 코퍼스에 따라 분포가 달라지며, 코사인 유사도는 보통 -1~1 범위에 몰려 있다. 단순 가중합을 쓰려면 정규화와 가중치 튜닝이 필요하고, 질의마다 분포가 달라 불안정하다. 이 지점에서 점수를 버리고 순위만 쓰는 RRF가 실용적인 대안이 된다.

## 5. RRF: 순위만으로 결합하기

Reciprocal Rank Fusion은 Cormack, Clarke, Buettcher가 SIGIR 2009에서 제안한 방법으로, 각 검색기에서 문서 d의 순위를 rank로 두고 score(d) = Σ 1/(k + rank)를 모든 검색기에 걸쳐 합산한다. 논문에서 제안한 상수는 k=60이다. 순위만 쓰므로 점수 척도를 맞출 필요가 없고, 한 검색기에서 아주 높은 순위를 받은 문서와 여러 검색기에서 고르게 상위인 문서가 모두 보상받는다. k는 상위 순위의 영향력을 조절한다. k가 작으면 1등과 2등의 차이가 크게 반영되고, 크면 순위 간 차이가 완만해진다.

```python
from collections import defaultdict
def rrf(rankings, k=60):
    s = defaultdict(float)
    for r in rankings:
        for i, d in enumerate(r, 1):
            s[d] += 1.0/(k+i)
    return sorted(s.items(), key=lambda x: (-x[1], x[0]))

bm25_rank  = ["d1","d2","d3","d4","d5"]
dense_rank = ["d3","d6","d1","d7","d2"]
for k in (60, 1):
    print(k, [(d, round(v,5)) for d,v in rrf([bm25_rank, dense_rank], k)[:5]])
# 60 [('d1', 0.03227), ('d3', 0.03227), ('d2', 0.03151), ('d6', 0.01613), ('d4', 0.01562)]
# 1  [('d1', 0.75), ('d3', 0.75), ('d2', 0.5), ('d6', 0.33333), ('d4', 0.2)]
```

결과를 보면 d1은 BM25 1위, 벡터 3위이고 d3은 BM25 3위, 벡터 1위여서 1/61+1/63과 1/63+1/61로 동점이 된다. 코드는 동점을 문서 id 사전순으로 깨도록 했는데, 운영에서는 결정적(deterministic) 순서를 보장해야 재현 가능한 평가가 가능하기 때문이다. 양쪽 모두 2위 이내인 d2는 0.03151로 3위에 올랐고, 한쪽에만 등장한 d6은 0.01613으로 밀렸다. k=1로 바꾸면 점수 격차가 크게 벌어지지만 이 toy 데이터에서는 순위 자체는 같았다. 실무에서는 각 검색기에서 후보를 50~100개 정도 깊게 뽑아 넣어야 RRF가 의미 있는 교집합을 만든다는 점, 가중치가 필요하면 검색기별 계수를 곱할 수 있지만 그 순간 튜닝 부담이 돌아온다는 점이 trade-off다.

## 6. 리랭킹: 2단계 검색으로 정밀도 올리기

1차 검색의 임베딩은 질의와 문서를 따로 인코딩하는 바이 인코더 구조라 빠르지만, 질의와 문서의 토큰 간 상호작용을 보지 못한다. 크로스 인코더 리랭커는 질의와 문서를 한 입력으로 합쳐 관련도 점수를 직접 예측하므로 정확도가 높은 대신 후보마다 모델을 한 번씩 실행해야 한다. 그래서 RRF로 줄인 상위 20~100개에만 적용하는 2단계 구조가 표준이다. 이때 리랭커는 후보 안에서 순서를 바로잡을 뿐이므로 정답이 후보 밖에 있으면 복구할 수 없다. 즉 1단계는 recall@N(N은 후보 수), 2단계는 정밀도 위주 지표로 따로 평가해야 한다.

```python
def rerank(query, candidates, scorer, top_n=5):
    """candidates: [(doc_id, text)], scorer(query, text)->float (cross-encoder 점수)"""
    scored = [(d, scorer(query, t)) for d, t in candidates]
    return [d for d, _ in sorted(scored, key=lambda x: -x[1])[:top_n]]
```

위 함수는 scorer만 바꾸면 사내 호스팅 크로스 인코더든 외부 리랭크 API든 같은 자리에 끼울 수 있다는 점을 보여 준다. 지연은 후보 수에 거의 선형으로 늘기 때문에 후보 수와 최대 입력 길이를 SLA에 맞춰 고정하고, 청크를 너무 크게 잡았다면 리랭킹 비용이 폭증한다는 점에서 2절의 청킹 결정과 연결된다. LLM을 리랭커로 쓰는 방식은 유연하지만 비용과 결정성 면에서 크로스 인코더보다 불리한 경우가 많다.

## 7. 평가 지표: recall@k, MRR, nDCG@k

검색 개선은 감으로 판단하면 쉽게 퇴보한다. 최소 수십 개의 질의와 각 질의의 정답 청크 라벨로 구성된 평가셋을 만들고 지표를 고정해 비교한다. recall@k는 정답 집합 중 상위 k개 안에 든 비율로, 1단계 후보 생성의 건강도를 본다. MRR(Mean Reciprocal Rank)은 첫 번째 정답이 나온 순위의 역수를 질의에 걸쳐 평균낸 값으로, 사용자가 첫 정답만 필요한 경우에 적합하다. nDCG@k는 관련도에 등급(예: 3, 1)을 줄 수 있고 순위가 낮을수록 log2 기반으로 할인하며, 이상적인 순서의 DCG로 나눠 0~1로 정규화한다.

```python
import math
def recall_at_k(ranked, rel, k): return len(set(ranked[:k]) & rel)/len(rel)
def mrr(ranked, rel):
    for i, d in enumerate(ranked, 1):
        if d in rel: return 1/i
    return 0.0
def ndcg_at_k(ranked, gains, k):
    dcg = sum(gains.get(d,0)/math.log2(i+1) for i, d in enumerate(ranked[:k], 1))
    ideal = sorted(gains.values(), reverse=True)[:k]
    idcg = sum(g/math.log2(i+1) for i, g in enumerate(ideal, 1))
    return dcg/idcg if idcg else 0.0

rel = {"d3","d1"}; gains = {"d3":3,"d1":1}
fused = [d for d, _ in rrf([bm25_rank, dense_rank])]
for name, r in [("bm25",bm25_rank),("dense",dense_rank),("rrf",fused)]:
    print(name, r[:5], "R@3=%.3f"%recall_at_k(r,rel,3), "MRR=%.3f"%mrr(r,rel), "nDCG@5=%.3f"%ndcg_at_k(r,gains,5))
# bm25  ['d1','d2','d3','d4','d5'] R@3=1.000 MRR=1.000 nDCG@5=0.689
# dense ['d3','d6','d1','d7','d2'] R@3=1.000 MRR=1.000 nDCG@5=0.964
# rrf   ['d1','d3','d2','d6','d4'] R@3=1.000 MRR=1.000 nDCG@5=0.797
```

정답이 d3(등급 3), d1(등급 1)인 이 toy 예에서 세 방식 모두 R@3과 MRR이 1.0이라 구분되지 않지만, nDCG@5는 벡터 0.964, RRF 0.797, BM25 0.689로 갈린다. 가장 관련도가 높은 d3을 얼마나 위에 두었는지를 nDCG만이 반영하기 때문이다. 이 데이터에서는 벡터 단독이 RRF보다 높게 나왔는데, 질의 한 건과 5개 후보라는 극소 표본의 결과이므로 하이브리드가 항상 우월하다는 근거로 읽으면 안 된다. 요점은 지표마다 보는 면이 다르니 recall로 후보 생성을, MRR·nDCG로 최종 순서를 보고, 질의 수를 늘려 평균과 분산을 함께 확인해야 한다는 것이다.

| 지표 | 보는 것 | 등급 관련도 | 적합한 용도 |
|---|---|---|---|
| recall@k | 정답을 후보에 포함했는가 | 불가 | 1단계 후보 깊이 결정 |
| MRR | 첫 정답의 위치 | 불가 | 단일 정답형 QA |
| nDCG@k | 순서 전체의 품질 | 가능 | 다중 근거, 등급 라벨 |

## 8. 설계 체크리스트와 운영 trade-off

파이프라인을 처음 세울 때는 가장 단순한 구성에서 시작해 지표로 이득이 확인되는 단계만 추가한다. 순서는 문장 경계 청킹과 오버랩 10~20%, BM25와 벡터 병렬 검색 후 RRF(k=60), 그다음 필요하면 상위 후보 리랭킹이다. 각 변경마다 동일한 평가셋으로 recall@N, MRR, nDCG@k를 기록해 회귀를 막는다. 평가셋은 실제 사용자 질의 로그에서 샘플링하되, 정답 라벨 작성 비용이 크므로 처음에는 50~100개 질의로 시작해 장애 사례가 나올 때마다 추가하는 방식이 현실적이다.

운영에서는 지연 예산을 단계별로 쪼개 둔다. 두 검색기는 병렬이라 느린 쪽이 전체를 결정하고, 리랭커는 후보 수에 비례해 늘어난다. 인덱스가 갱신될 때 BM25 통계와 벡터 인덱스가 어긋나지 않게 같은 청크 id를 공유해야 RRF가 같은 문서를 같은 키로 합산한다. 임베딩 모델을 교체하면 전체 재색인이 필요하고 점수 분포가 바뀌므로, 점수 임계치를 쓰는 로직이 있다면 함께 다시 보정한다. 마지막으로 Multi-Agent 환경에서는 에이전트가 같은 검색 도구를 서로 다른 질의 스타일로 호출하므로, 에이전트별 질의 샘플을 평가셋에 섞어 두면 특정 에이전트만 성능이 떨어지는 문제를 조기에 발견할 수 있다.

## 참고

- Cormack, Clarke, Buettcher, "Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods", SIGIR 2009 - https://dl.acm.org/doi/10.1145/1571941.1572114
- Robertson, Zaragoza, "The Probabilistic Relevance Framework: BM25 and Beyond", Foundations and Trends in IR, 2009 - https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf
- Lewis et al., "Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks", NeurIPS 2020 - https://arxiv.org/abs/2005.11401
- Nogueira, Cho, "Passage Re-ranking with BERT", 2019 - https://arxiv.org/abs/1901.04085
- Thakur et al., "BEIR: A Heterogenous Benchmark for Zero-shot Evaluation of Information Retrieval Models", NeurIPS 2021 - https://arxiv.org/abs/2104.08663
- Malkov, Yashunin, "Efficient and robust approximate nearest neighbor search using HNSW graphs", 2016 - https://arxiv.org/abs/1603.09320
- Järvelin, Kekäläinen, "Cumulated Gain-Based Evaluation of IR Techniques", ACM TOIS 2002 - https://dl.acm.org/doi/10.1145/582415.582418
