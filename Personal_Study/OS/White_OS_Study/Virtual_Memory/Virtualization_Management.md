# Virtualization Management

![Untitled](Virtualization_Management/Untitled.png)

# 정의

- 가상화 관리는 가상 환경 및 기본 물리적 하드웨어와 인터페이스하여 리소스 관리를 단순화하고 데이터 분석을 강화하며 운영을 간소화하는 소프트웨어다.
    - 가상화 환경의 운영과 프로세스를 감독, 관리하는 프로세스. 가상화된 인프라에 대한 거버넌스(Governance) 및 제어를 보장하기 위한 집합적인 프로세스, 도구 및 기술을 포함하는 IT 관리의 일부.
- 각 가상화 관리 시스템은 고유하지만 대부분 복잡하지 않은 사용자 인터페이스를 갖추고 있으며, 다음과 같은 프로세스가 포함될 수 있다.
    - 가상 머신, 가상 네트워크 및 전체 가상화 인프라의 생성, 삭제 및 수정
    - 모든 가상 머신 소프트웨어/하이퍼바이저가 설치된 OS 및 애플리케이션과 함께 최신상태 확인
    - 가상화 환경 전반에 걸쳐 네트워크 연결, 상호 연결을 설정하고 유지
    - 각 가상 머신 및 가상화 환경의 성능을 전체적으로 모니터링하고 관리
- 가상화 관리 시스템은 가상 머신의 하이퍼바이저와 상호 작용하여 작동한다.
    - 가상화 관리 시스템은 하이퍼바이저와 동기화하여 모든 가상 머신의 상태에 대한 정보를 얻는다.
    - 그 뒤 시스템은 해당 정보를 사용자가 이해할 수 있는 방식으로, 차트와 그래프 등으로 사용자에게 보낸다.
    - 가상 머신의 상태에 대한 정보를 얻는 것 외에도 가상화 관리에는 가상 머신의 작업 조정 및 관리와 같은 다른 모든 관리 활동도 포함된다.
- 소수의 가상 머신은 수동으로 관리할 수 있지만 대규모 기업 전체 배포에서는 가상화 관리 소프트웨어의 사용 필요성이 높아진다.
    - 이는 하이퍼바이저가 설치될 때 VM이 자동으로 프로비저닝(Provisioned)되는 것이 아니기 때문이다.
    - 여기에는 일반적으로 새 VM이 실행될 때마다 늘어나는 4가지 책임의 조합이 포함된다.
        - 프로비저닝
            - 리소스 요청 처리, 템플릿 생성, VM 구성
        - 규정 준수
            - 시스템 보안 및 모니터링, 문제 식별, 사용자 액세스 유효성 검사
        - 운영
            - 사용되지 않거나 충분히 사용되지 않는 물리적 리소스 폐기하거나 회수하고, 버그를 조사하고, 향후 요구 사항을 예측
        - 하이브리드 통합
            - 가상, 프라이빗 클라우드, 퍼블릭 클라우드, 컨테이너 환경 전반에 걸쳐 프로비저닝, 규정 준수, 운영을 구현한다

# 장점

- 운영 비용 절감
    - 시스템의 모든 부분을 차례로 모니터링하는 것에 비해, 가상화 관리를 사용함으로써 에코시스템의 모든 시스템을 모니터링하고 관리할 수 있다.
- 시스템 최적화
    - 가상화 관리 시스템을 통해 시스템을 최적화하여 성능을 향상시킬 수 있다.
    - 또한 시스템 가동 중지 시간이 발생할 가능성을 낮추는 데도 도움이 된다.
    - 이는 가상화 관리 시스템이 시스템 성능과 시스템에 내재된 리소스의 균형을 재조정하여 전반적인 성능을 향상시키는 경향이 있기 때문이다.
    - 또한, 전체 시스템을 확인하여 시스템이 보유한 숨겨진 리소스 등의 잠금을 해제할 수 있는 기능도 있다.
- 전체 시스템 모니터링
    - 가상화 관리 머신이 전체 시스템을 지속적으로 모니터링하므로 가상화 관리가 기존의 DevOps 구성원이 처리하던 IT 환경을 유지하고 실행하는 일의 대부분을 처리할 수 있다.
- 결함 감지 및 해결
    - 가상화 관리는 시스템의 이상 징후를 감지하는 데 도움이 된다.
    - 또한 문제가 전체 시스템에 큰 타격을 주기 전에 문제를 진단하고 해결하는 경향이 있다.
    - 이는 시스템 가동 중지 시간이 발생할 가능성을 완화하는 좋은 방법이기도 하다.