Notion 원본: https://www.notion.so/3745a06fd6d381b983ccdf6df19a2dc9

# Kubernetes Native Sidecar Container v1.29 GA와 Init Container restartPolicy Always

> 2026-06-03 신규 주제 · 확장 대상: Docker&CI

## 학습 목표

- v1.29 에서 GA 된 native sidecar 가 init container 의 `restartPolicy: Always` 변종으로 구현된 이유를 따라간다
- Job/CronJob 에서 sidecar 가 main container 종료 후 자동 종료되는 흐름을 yaml + 이벤트로 확인한다
- Istio / Linkerd / Envoy / fluentbit 같은 기존 sidecar 가 native sidecar 로 마이그레이션될 때의 변화점을 정리한다
- Pod lifecycle hook 과 startup ordering 의 상호작용을 검증한다

## 1. 왜 새로운 sidecar 모델이 필요했는가

Kubernetes 초기부터 "sidecar" 는 *관습* 일 뿐 spec 의 일등 개념이 아니었다. 한계: 첫째 시작 순서 보장 없음. 둘째 종료 순서 망가짐. 셋째 Job 에서 sidecar 가 영원히 살아남음. v1.29 의 native sidecar 가 셋 다 해결한다.

## 2. Init Container 의 restartPolicy: Always

```yaml
apiVersion: v1
kind: Pod
spec:
  initContainers:
  - name: istio-proxy
    image: docker.io/istio/proxyv2:1.24.0
    restartPolicy: Always
    startupProbe:
      httpGet: { path: /healthz/ready, port: 15021 }
      periodSeconds: 1
      failureThreshold: 30
  containers:
  - name: app
    image: myapp:1.0
```

핵심 의미 셋: startup ordering, shutdown ordering, Job 에서 자동 종료.

## 3. v1.29 GA 까지의 단계

v1.28 alpha (default off). v1.29 beta + default on. v1.33 GA, feature gate 제거. EKS / GKE / AKS 모두 v1.29 이상 default 활성.

## 4. Job / CronJob 의 sidecar 종료 흐름

```yaml
apiVersion: batch/v1
kind: Job
spec:
  template:
    spec:
      restartPolicy: Never
      initContainers:
      - name: fluentbit
        image: fluent/fluent-bit:3
        restartPolicy: Always
        lifecycle:
          preStop:
            exec:
              command: ["/bin/sh", "-c", "fluent-bit-flush.sh"]
      containers:
      - name: worker
        image: worker:1.0
```

worker exit → kubelet 이 fluentbit 에 SIGTERM → preStop 으로 log flush → fluentbit exit → Pod Completed → Job Completed.

## 5. lifecycle hook 와 native sidecar

`postStart` / `preStop` hook 은 native sidecar 에서도 동작. 총 termination grace period 는 `spec.terminationGracePeriodSeconds` (default 30s). preStop 의 sleep 이 grace period 를 넘으면 SIGKILL. grace period 60s 로 늘리는 게 일반적.

## 6. Istio Ambient vs sidecar — 어디로 가는가

| 항목 | 기존 sidecar | native sidecar | Ambient (ztunnel) |
|---|---|---|---|
| Pod 당 컨테이너 수 | 2+ | 2+ | 1 |
| memory overhead | ~80 MB/pod | ~80 MB/pod | ~5 MB/pod |
| L4 / L7 분리 | 둘 다 sidecar | 둘 다 sidecar | L4=ztunnel, L7=waypoint |
| 채택 시기 | 2018~ | 2024~ | 2024~ Beta |

판단: 기존 Istio 운영팀 → native sidecar 마이그레이션이 최소. 새 mesh 도입 → Ambient.

## 7. Pod lifecycle 의 전체 그림

시작: volume mount → 전통 init container → native sidecar (startupProbe 통과 대기) → main containers. 종료: main → native sidecar (역순) → SIGKILL. v1.29 부터 spec 단어로 보장.

## 8. 마이그레이션 — 기존 sidecar yaml 변환

`containers` → `initContainers` 로 이동, `restartPolicy: Always` 추가, `startupProbe` 추가, `preStop` 추가. Helm chart / operator 도 v1.29 호환 release 부터 위 패턴 적용. fluent-bit chart 3.2+, istiod 1.24+, linkerd 2.16+ 지원.

## 9. Trade-off 와 실측

장점: 기존 sidecar 의 race condition 4 종 (startup, shutdown, Job, preStop ordering) 이 모두 정리. 단점: startupProbe 미설정 sidecar 가 main 의 시작을 막아 Pod 시작이 는려짐 — 사내 *median +2.3 s, p99 +6.1 s*. v1.30+ 권장. cluster v1.29+ 이고 sidecar 운영 중이라면 *반드시* native sidecar 로 마이그레이션.

## 참고

- KEP-753 Sidecar Containers: https://github.com/kubernetes/enhancements/tree/master/keps/sig-node/753-sidecar-containers
- Kubernetes Docs — Sidecar Containers
- Istio Ambient Mesh
- v1.29 Release Notes
- v1.33 GA Notes
