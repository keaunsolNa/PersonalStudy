Notion 원본: https://app.notion.com/p/3bb5a06fd6d381ab9104c44cc64cab89?pvs=204

# Kubernetes CSI 드라이버 아키텍처와 동적 프로비저닝 및 볼륨 스냅샷

> 2026-08-13 신규 주제 · 확장 대상: Docker&CI, AWS

## 학습 목표

- CSI 의 Identity/Controller/Node 분리와 gRPC 호출 순서로 볼륨 수명주기를 설계한다.
- provisioner·attacher·resizer·snapshotter 사이드카를 조합해 드라이버를 배포한다.
- volumeBindingMode·allowVolumeExpansion·토폴로지 제약으로 AZ 불일치를 예방한다.
- 스냅샷 파이프라인과 온라인 확장을 운영하고 multi-attach·finalizer 장애를 진단한다.

## 1. CSI 가 해결한 문제와 3개 서비스 분해

초기 Kubernetes 는 스토리지 드라이버를 코어 바이너리에 직접 컴파일했다. 이 in-tree 방식에서는 벤더가 버그 하나를 고치려 해도 릴리스 사이클을 기다려야 했다. CSI 는 오케스트레이터와 스토리지 제공자 사이의 gRPC 계약을 표준화해 이 결합을 끊었다. 스펙은 세 서비스로 나뉜다. `Identity` 는 어디서든 필수이며 드라이버 이름과 능력을 알리고, `Controller` 는 클러스터에서 한 번만 떠서 백엔드 API 를 호출하며, `Node` 는 볼륨을 쓸 노드마다 떠서 포맷·마운트 같은 커널 작업을 한다.

```protobuf
service Identity {
  rpc GetPluginInfo(...) returns (...) {}
  rpc GetPluginCapabilities(...) returns (...) {} // CONTROLLER_SERVICE, VOLUME_ACCESSIBILITY_CONSTRAINTS
  rpc Probe(...) returns (...) {}                 // liveness-probe 사이드카가 주기 호출
}
service Controller {
  rpc CreateVolume(...) returns (...) {}
  rpc DeleteVolume(...) returns (...) {}
  rpc ControllerPublishVolume(...) returns (...) {}   // = attach
  rpc ControllerExpandVolume(...) returns (...) {}
  rpc CreateSnapshot(...) returns (...) {}
}
service Node {
  rpc NodeStageVolume(...) returns (...) {}    // 노드당 1회, 글로벌 마운트
  rpc NodePublishVolume(...) returns (...) {}  // 파드당 1회, 바인드 마운트
  rpc NodeExpandVolume(...) returns (...) {}
  rpc NodeGetInfo(...) returns (...) {}        // nodeID + topology 반환
}
```

자주 어긋나는 지점은 능력 광고와 사이드카 배포의 짝이다. `CONTROLLER_SERVICE` 를 광고하지 않으면 attacher 를 붙여도 VolumeAttachment 가 생기지 않고, 반대로 attach 개념이 없는 NFS 계열에 붙이면 쓸모없는 finalizer 만 늘어 삭제가 느려진다.

## 2. 볼륨 수명주기 gRPC 호출 흐름

PVC 가 파드에 마운트되기까지는 네 단계다. `CreateVolume` 이 백엔드에 볼륨을 만들고, `ControllerPublishVolume` 이 노드에 attach 하며, `NodeStageVolume` 이 디바이스를 포맷해 globalmount 에 올리고, `NodePublishVolume` 이 그것을 파드 디렉터리로 bind mount 한다. 3·4 를 나눈 이유는 같은 노드의 여러 파드가 한 볼륨을 쓸 때 포맷과 마운트를 한 번만 하고 파드별로는 저렴한 bind mount 만 반복하기 위해서다. 삭제는 역순이며 `CreateVolume` 은 멱등이어야 한다.

| 단계 | 호출 주체 | 트리거 | 실패 시 증상 |
| --- | --- | --- | --- |
| CreateVolume | external-provisioner | PVC | PVC Pending, ProvisioningFailed |
| ControllerPublishVolume | external-attacher | VolumeAttachment | FailedAttachVolume |
| NodeStageVolume | kubelet → node plugin | 스케줄 확정 | FailedMount, mkfs 에러 |
| NodePublishVolume | kubelet → node plugin | 컨테이너 시작 직전 | FailedMount, permission denied |

주체가 다르면 로그 위치도 다르다. `CreateVolume` 실패는 컨트롤러의 provisioner 사이드카에, `NodeStageVolume` 실패는 노드 DaemonSet 파드에 남는다.

```bash
kubectl -n kube-system logs deploy/ebs-csi-controller -c csi-provisioner --tail=200
kubectl -n kube-system logs ds/ebs-csi-node -c ebs-plugin
kubectl get volumeattachment -o custom-columns=\
NAME:.metadata.name,PV:.spec.source.persistentVolumeName,NODE:.spec.nodeName,ATTACHED:.status.attached
```

## 3. 사이드카 구성과 in-tree CSI Migration

드라이버는 gRPC 소켓만 노출하고, Kubernetes 오브젝트를 감시해 gRPC 로 번역하는 일은 커뮤니티 사이드카가 맡는다. provisioner 는 PVC/PV, attacher 는 VolumeAttachment, resizer 는 용량 변경, snapshotter 는 VolumeSnapshot 을 감시하고, node-driver-registrar 는 kubelet 등록 디렉터리에 소켓 경로를 등록한다.

```yaml
# controller Deployment 발췌 - 사이드카와 드라이버가 UNIX 소켓 공유
spec:
  containers:
    - name: csi-provisioner
      image: registry.k8s.io/sig-storage/csi-provisioner:v5.0.1
      args:
        - --csi-address=/csi/csi.sock
        - --feature-gates=Topology=true
        - --extra-create-metadata      # PVC 이름을 백엔드 태그로 전달
        - --leader-election
      volumeMounts: [{ name: socket-dir, mountPath: /csi }]
    - name: csi-attacher
      image: registry.k8s.io/sig-storage/csi-attacher:v4.6.1
      args: [--csi-address=/csi/csi.sock, --leader-election]
    - name: ebs-plugin
      image: public.ecr.aws/ebs-csi-driver/aws-ebs-csi-driver:v1.31.0
      args: [controller, --endpoint=unix:/csi/csi.sock]
  volumes:
    - { name: socket-dir, emptyDir: {} }
```

노드 측은 DaemonSet 이고, kubelet 이 호스트 경로로 소켓에 접근하므로 소켓 디렉터리는 emptyDir 이 아닌 hostPath 여야 한다.

```yaml
# node DaemonSet 발췌
containers:
  - name: node-driver-registrar
    image: registry.k8s.io/sig-storage/csi-node-driver-registrar:v2.11.0
    args:
      - --csi-address=/csi/csi.sock
      - --kubelet-registration-path=/var/lib/kubelet/plugins/ebs.csi.aws.com/csi.sock
  - name: ebs-plugin
    securityContext: { privileged: true }   # mount(2), mkfs 수행에 필요
    volumeMounts:
      - name: kubelet-dir
        mountPath: /var/lib/kubelet
        mountPropagation: Bidirectional
volumes:
  - { name: kubelet-dir, hostPath: { path: /var/lib/kubelet, type: Directory } }
  - { name: registration-dir, hostPath: { path: /var/lib/kubelet/plugins_registry, type: Directory } }
```

in-tree 플러그인은 CSI Migration 으로 대체됐다. 기존 매니페스트가 `kubernetes.io/aws-ebs` 를 써도 마이그레이션이 켜진 클러스터에서는 `ebs.csi.aws.com` 호출로 자동 번역된다. 다만 드라이버가 설치돼 있어야 하고 암호화·IOPS 등 일부 파라미터는 1:1 대응하지 않으므로, 신규 SC 는 CSI provisioner 이름으로 직접 쓰는 편이 예측 가능하다.

## 4. StorageClass 설계 - 바인딩 모드와 확장 허용

`provisioner` 는 처리 주체를, `parameters` 는 드라이버에 그대로 전달되는 백엔드 옵션을 지정한다. parameters 의 키는 CSI 스펙이 아니라 드라이버가 정의한다.

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: { name: gp3-encrypted }
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  iops: "6000"
  throughput: "250"
  encrypted: "true"
  kmsKeyId: arn:aws:kms:ap-northeast-2:111122223333:key/abcd-ef01
  csi.storage.k8s.io/fstype: xfs
volumeBindingMode: WaitForFirstConsumer
allowVolumeExpansion: true
reclaimPolicy: Delete
allowedTopologies:
  - matchLabelExpressions:
      - key: topology.ebs.csi.aws.com/zone
        values: ["ap-northeast-2a", "ap-northeast-2c"]
```

기본값 `Immediate` 는 PVC 생성 즉시 볼륨을 만드는데, 이때 스케줄러는 파드 위치를 모른다. EBS 처럼 AZ 에 묶이는 스토리지에서 볼륨이 2a 에 생겼는데 파드가 2c 로 가면 영원히 attach 되지 않는다. `WaitForFirstConsumer` 는 스케줄될 때까지 PVC 를 Pending 으로 두고 확정된 노드의 토폴로지를 `CreateVolume` 의 accessibility_requirements 로 넘기므로 존 종속 스토리지에서는 사실상 필수다.

`allowVolumeExpansion` 은 나중에 켜도 이미 바인딩된 PV 에 소급되지 않으므로 처음부터 켜두는 편이 안전하다. 축소는 어떤 드라이버도 지원하지 않아, 잘못 늘린 용량은 새 PVC 로 복사하는 것 외에 되돌릴 방법이 없다.

## 5. 토폴로지 인식 스케줄링과 PV/PVC 바인딩

`NodeGetInfo` 응답의 토폴로지 키-값은 CSINode 에 기록된다. provisioner 가 Topology 를 켜면 이를 `CreateVolume` 에 넘기고 생성된 PV 에 nodeAffinity 가 자동으로 박히며, 스케줄러가 이 affinity 로 노드를 걸러내 볼륨이 없는 AZ 로는 파드가 가지 못한다.

```yaml
# 프로비저닝 결과 PV 발췌 - 사용자가 쓰지 않아도 자동 생성된다
spec:
  capacity: { storage: 100Gi }
  csi:
    driver: ebs.csi.aws.com
    volumeHandle: vol-0a1b2c3d4e5f
    fsType: xfs
  nodeAffinity:
    required:
      nodeSelectorTerms:
        - matchExpressions:
            - key: topology.ebs.csi.aws.com/zone
              operator: In
              values: ["ap-northeast-2a"]
```

부작용은 재스케줄 유연성 상실이다. 2a 노드가 모두 빠지면 그 볼륨을 쓰는 파드는 Pending 에 머무므로, Cluster Autoscaler 를 쓴다면 노드 그룹을 AZ 별로 나눠야 한다.

`ReadWriteOnce` 는 "노드 하나"를 뜻하므로 같은 노드의 다른 파드가 동시에 쓸 수 있어 단일 라이터를 강제해야 하는 DB 에는 위험하다. `ReadWriteOncePod` 는 파드 하나만 마운트하게 해 롤링 업데이트 중 구·신 파드가 겹칠 때 새 파드를 확실히 대기시킨다.

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata: { name: pg-data }
spec:
  accessModes: ["ReadWriteOncePod"]
  storageClassName: gp3-encrypted
  resources: { requests: { storage: 100Gi } }
```

## 6. VolumeSnapshot 파이프라인과 복원

스냅샷은 네임스페이스 스코프의 `VolumeSnapshot`, 클러스터 스코프의 `VolumeSnapshotContent`, 템플릿인 `VolumeSnapshotClass` 로 구성되며 PVC/PV 관계를 그대로 옮긴 구조다. 이 CRD 와 snapshot-controller 는 CSI 드라이버와 별개로 설치해야 한다. 스냅샷을 만들었는데 아무 일도 없는 사고는 대개 CRD 만 있고 컨트롤러가 없어서다.

```yaml
apiVersion: snapshot.storage.k8s.io/v1
kind: VolumeSnapshotClass
metadata: { name: ebs-snapclass }
driver: ebs.csi.aws.com
deletionPolicy: Retain          # 스냅샷은 Retain 권장
---
apiVersion: snapshot.storage.k8s.io/v1
kind: VolumeSnapshot
metadata: { name: pg-data-2026-08-13 }
spec:
  volumeSnapshotClassName: ebs-snapclass
  source: { persistentVolumeClaimName: pg-data }
---
# 복원: dataSource 로 스냅샷을 지정한 새 PVC
apiVersion: v1
kind: PersistentVolumeClaim
metadata: { name: pg-data-restored }
spec:
  accessModes: ["ReadWriteOnce"]
  storageClassName: gp3-encrypted
  dataSource:
    name: pg-data-2026-08-13
    kind: VolumeSnapshot
    apiGroup: snapshot.storage.k8s.io
  resources: { requests: { storage: 100Gi } }   # 원본 이상이어야 한다
```

CSI 스냅샷은 크래시 정합성만 보장한다. 전원이 갑자기 끊긴 디스크와 같으므로 애플리케이션 정합성이 필요하면 직전에 DB 를 quiesce 해야 한다. 또 EBS 스냅샷에서 복원한 볼륨은 초기 블록 접근이 느려 복원 직후 벤치마크로 성능을 판단하면 오판하기 쉽다.

```bash
kubectl get volumesnapshot pg-data-2026-08-13 \
  -o jsonpath='{.status.readyToUse}{"\t"}{.status.restoreSize}{"\n"}'
kubectl get volumesnapshotcontent -o wide
```

## 7. 온라인 볼륨 확장과 파일시스템 리사이즈

확장은 백엔드 디바이스를 키우는 `ControllerExpandVolume` 과 파일시스템을 늘리는 `NodeExpandVolume` 두 층이다. PVC 의 요청 용량만 키우면 external-resizer 가 1단계를 수행하고, 온라인 확장을 지원하는 드라이버면 kubelet 이 파드를 내리지 않고 `resize2fs`/`xfs_growfs` 를 실행한다. 미지원이면 `FileSystemResizePending` 조건이 남아 파드 재시작이 필요하다.

```bash
kubectl patch pvc pg-data -p '{"spec":{"resources":{"requests":{"storage":"200Gi"}}}}'
kubectl get pvc pg-data -o jsonpath='{.status.capacity.storage}{"\n"}'
kubectl describe pvc pg-data | grep -A4 Conditions
kubectl exec -it postgres-0 -- df -h /var/lib/postgresql/data
```

`spec.requests` 만 커지고 `status.capacity` 가 옛 값이면 진행 중이다. status 는 늘었는데 `df` 가 그대로면 파일시스템 리사이즈가 남은 것이고, EBS 는 볼륨 수정 후 일정 시간 재수정을 거부하므로 조금씩 여러 번 늘리면 다음 확장이 막힌다. raw block 볼륨은 노드 단계 확장이 없어 애플리케이션이 크기 변화를 직접 인지해야 한다.

## 8. StatefulSet 볼륨 재사용과 삭제 정책, 장애 진단

`volumeClaimTemplates` 는 `<template>-<statefulset>-<ordinal>` 규칙으로 PVC 를 만든다. 이름이 결정적이라 파드가 재생성돼도 같은 PVC 를 다시 잡는 반면, StatefulSet 을 지워도 PVC 는 남아 개발 클러스터에서는 고아 PVC 로 비용이 샌다. `persistentVolumeClaimRetentionPolicy` 로 삭제·축소 시 동작을 지정한다.

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: postgres }
spec:
  serviceName: postgres
  replicas: 3
  persistentVolumeClaimRetentionPolicy:
    whenDeleted: Retain     # 운영은 Retain
    whenScaled: Delete      # 축소한 ordinal 의 PVC 는 정리
  volumeClaimTemplates:
    - metadata: { name: data }
      spec:
        accessModes: ["ReadWriteOncePod"]
        storageClassName: gp3-encrypted
        resources: { requests: { storage: 100Gi } }
```

PV 의 `reclaimPolicy` 는 다른 층위다. `Delete` 는 PVC 삭제 시 백엔드 볼륨까지 지우고, `Retain` 은 PV 를 `Released` 로 남긴다. Retain 된 PV 는 자동 재바인딩되지 않아 `claimRef` 를 지워 `Available` 로 되돌려야 한다.

```bash
kubectl patch pv pvc-3f2a... -p '{"spec":{"persistentVolumeReclaimPolicy":"Retain"}}'
kubectl patch pv pvc-3f2a... --type=json -p '[{"op":"remove","path":"/spec/claimRef"}]'
```

삭제가 멈추는 원인은 대개 finalizer 다. PVC 의 `kubernetes.io/pvc-protection`, PV 의 `kubernetes.io/pv-protection` 이 사용 중 삭제를 막는다. 참조 파드가 없는데도 Terminating 이면 `DeleteVolume` 이 백엔드 오류로 실패하는 중일 가능성이 높다. finalizer 를 강제로 지우면 클라우드 볼륨이 고아로 남아 요금이 계속 나가므로 백엔드 정리와 함께 최후 수단으로만 쓴다.

| 증상 | 유력 원인 | 확인 방법 |
| --- | --- | --- |
| `Multi-Attach error for volume` | 이전 노드가 NotReady 라 detach 미완료 | VolumeAttachment 의 nodeName 과 노드 상태 대조 |
| `driver name not found in the list of registered CSI drivers` | node-driver-registrar 실패 | 노드 DaemonSet 로그, CSINode 확인 |
| 마운트는 성공했는데 경로가 빔 | mountPropagation 누락 | DaemonSet 의 Bidirectional 설정 확인 |
| PVC 가 계속 Pending | WaitForFirstConsumer + 스케줄 불가 | `kubectl describe pod` 스케줄 이벤트 |

multi-attach 는 노드 장애 시 잦다. 노드가 응답하지 않으면 컨트롤러가 손상을 우려해 강제 detach 를 지연시키고 새 노드의 파드는 attach 실패로 대기한다. 정상 절차는 노드 오브젝트가 제거돼 컨트롤러가 정리하게 두는 것이며, VolumeAttachment 를 직접 지우는 우회는 옛 노드가 쓰기 중일 때 파일시스템을 손상시킨다.

mount propagation 은 덜 직관적이다. 노드 플러그인이 만든 마운트가 컨테이너 네임스페이스 밖으로 전파돼야 kubelet 이 인식하므로, `Bidirectional` 이 아니면 드라이버는 성공해도 kubelet 에는 빈 디렉터리로 보인다.

## 참고

- [Kubernetes 공식 문서 - Storage Classes](https://kubernetes.io/docs/concepts/storage/storage-classes/)
- [Kubernetes 공식 문서 - Volume Snapshots](https://kubernetes.io/docs/concepts/storage/volume-snapshots/)
- [Kubernetes 공식 문서 - Persistent Volumes](https://kubernetes.io/docs/concepts/storage/persistent-volumes/)
- [Kubernetes CSI Developer Documentation](https://kubernetes-csi.github.io/docs/)
- [CSI Specification (GitHub)](https://github.com/container-storage-interface/spec/blob/master/spec.md)
- [AWS EBS CSI Driver (GitHub)](https://github.com/kubernetes-sigs/aws-ebs-csi-driver)
- [external-snapshotter (GitHub)](https://github.com/kubernetes-csi/external-snapshotter)
