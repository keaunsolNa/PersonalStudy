Notion 원본: https://app.notion.com/p/3ab5a06fd6d38143910ee2929b1c48d8

# Kubernetes StatefulSet과 순서보장 및 헤드리스 서비스 볼륨클레임

> 2026-07-28 신규 주제 · 확장 대상: Docker&CI / Kubernetes(워크로드 컨트롤러)

## 학습 목표

- StatefulSet 이 Deployment 와 달리 안정적 네트워크 ID·순서 보장·영속 스토리지를 제공하는 이유를 구분한다.
- 헤드리스 서비스가 Pod 별 DNS 를 부여해 stable identity 를 완성하는 메커니즘을 정리한다.
- volumeClaimTemplates 로 Pod 마다 전용 PVC 가 바인딩되고 재스케줄에도 유지되는 원리를 파악한다.
- 순차/병렬 롤아웃 정책과 스케일다운 시 데이터 안전을 실측 관점에서 판단한다.

## 1. 왜 Deployment 로는 부족한가

Deployment 는 Pod 를 교체 가능한 소모품으로 다룬다. 이름은 랜덤 해시이고 스토리지는 대개 공유하거나 없다. DB·카프카·주키퍼처럼 각 인스턴스가 고유한 정체성과 자기 데이터를 가져야 하는 워크로드에는 맞지 않는다. StatefulSet 은 안정적 이름(mysql-0), 순서 보장, Pod 별 영속 스토리지 세 가지를 보장한다.

## 2. 헤드리스 서비스 — Pod 별 DNS

헤드리스 서비스(clusterIP: None)는 VIP 없이 각 Pod 의 IP 를 직접 DNS 로 노출한다.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: mysql
spec:
  clusterIP: None
  selector:
    app: mysql
  ports:
    - port: 3306
```

각 Pod 은 pod-name.service-name.namespace.svc.cluster.local DNS 를 얻는다(예: mysql-0.mysql.default.svc.cluster.local). Pod 이 재스케줄되어 IP 가 바뀌어도 DNS 이름은 그대로라 복제 토폴로지를 이름으로 고정할 수 있다.

## 3. volumeClaimTemplates — Pod 전용 볼륨

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: mysql
spec:
  serviceName: mysql
  replicas: 3
  podManagementPolicy: OrderedReady
  template:
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes: ["ReadWriteOnce"]
        resources:
          requests:
            storage: 50Gi
```

PVC 생명주기가 Pod 과 분리되어 Pod 이 재생성돼도 같은 PVC 가 다시 붙어 데이터가 살아남는다. 스케일다운·삭제해도 PVC 는 자동으로 지워지지 않는다.

## 4. 순서 보장과 롤아웃 정책

기본 OrderedReady 는 생성을 0->1->2 순으로, 삭제를 역순 2->1->0 으로 강제한다. Parallel 로 바꾸면 동시 처리한다. RollingUpdate 의 partition 으로 카나리를 구현한다.

```yaml
spec:
  updateStrategy:
    type: RollingUpdate
    rollingUpdate:
      partition: 2   # mysql-2 만 새 버전
```

## 5. 스케일다운 시 데이터 안전

replicas 를 3->1 로 줄이면 mysql-2, mysql-1 이 사라지지만 PVC 는 남아 다시 확장하면 데이터가 복원된다. quorum 클러스터는 preStop 훅으로 애플리케이션 레벨 이탈을 먼저 처리해야 안전하다.

## 6. 관찰과 검증

```bash
kubectl get pods -l app=mysql -w      # 순차 기동 확인
kubectl get pvc -l app=mysql          # Pod별 PVC 바인딩 확인
kubectl run -it --rm dns-test --image=busybox --restart=Never -- nslookup mysql-0.mysql.default.svc.cluster.local
```

## 7. 정리 — 정체성이 필요한 워크로드의 컨트롤러

StatefulSet 은 각 Pod 이 누구인지가 중요한 워크로드를 위한 컨트롤러다. 안정적 이름·순서·전용 볼륨이 삼위일체로 상태 저장 시스템의 정체성을 만든다.

## 8. PodDisruptionBudget — 자발적 중단으로부터 quorum 보호

kubectl drain 같은 자발적 중단 시 여러 Pod 이 동시에 축출되면 quorum 을 잃는다. PDB 는 동시에 몇 개까지 내려도 되는가의 하한을 강제한다.

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: mysql-pdb
spec:
  minAvailable: 2
  selector:
    matchLabels: { app: mysql }
```

PDB 는 자발적 중단만 막으므로 내결함성은 replica 수와 anti-affinity 로 별도 확보한다.

## 9. 볼륨 스냅샷과 확장

CSI VolumeSnapshot 으로 PVC 시점 스냅샷을 만들고 복원한다. StorageClass 가 allowVolumeExpansion: true 면 PVC storage 요청을 키워 온라인 확장한다. 템플릿 갱신은 --cascade=orphan 으로 StatefulSet 만 삭제해 무중단 우회한다.

```bash
kubectl delete statefulset mysql --cascade=orphan
```

## 10. Deployment 와의 최종 비교

| 항목 | Deployment | StatefulSet |
|---|---|---|
| Pod 이름 | 랜덤 해시 | 순서 인덱스 고정 |
| 네트워크 ID | VIP 뒤 익명 | Pod별 DNS |
| 스토리지 | 공유/무상태 | Pod 전용 PVC 유지 |
| 생성·삭제 순서 | 병렬 | OrderedReady 순차 |
| 대표 워크로드 | 웹·API | DB·카프카·레디스 |

결정 규칙은 각 인스턴스가 서로 구별되는 정체성과 자기 데이터를 가져야 하는가이다. StatefulSet 은 정체성 인프라만 제공하며 복제·리더선출은 오퍼레이터의 몫이다.

## 참고

- Kubernetes Docs — StatefulSets — https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/
- Kubernetes Docs — Headless Services — https://kubernetes.io/docs/concepts/services-networking/service/#headless-services
- Kubernetes Docs — StatefulSet Basics — https://kubernetes.io/docs/tutorials/stateful-application/basic-stateful-set/
- Kubernetes Docs — Persistent Volumes — https://kubernetes.io/docs/concepts/storage/persistent-volumes/
