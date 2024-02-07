# TCB(Thread Control Block)

![Untitled](TCB(Thread%20Control%20Block)%20cae37296ce914a77b2bb2057bcf48f6a/Untitled.png)

# Definition

- TCB ⇒ Thread를 Control 하는 구조체(자료 구조)
- TCB는 PCB와 매우 유사한 구조로 시스템에서 생성된 thread를 나타낸다.
- ID 및 상태와 같은 thread에 대한 정보가 포함된다.
- thread 라이브러리에 의해 문맥 교환 되는 thread 정보의 단위다.
- PCB 내 TCB를 수용하는 것으로 kernel은 thread를 실현 시킨다.
- Thread가 하나 생성될 때마다 PCB 내에서 TCB가 확장된다.

# TCB의 역할

- TCB의 신규 등록(Thread 생성)은 생각보다 많은 Resource가 필요하다.
- 개별 프로세스에서 수행하는 각각의 명령마다 하나의 Thread를 만드는 것은 자원의 낭비로 이어진다.
- 그렇기에 보통 현대 OS는(특히 게임서버처럼 부하가 큰 서버는) 미리 Thread를 생성하고 제어하는 방식을 취한다.
- 평상시에는 생성되어 있는 Thread를 Waiting(Sleep) 상태로 두고 있다가 부하가 있을 때 Waiting 상태의 Thread를 active 시킨다.
- TCB는 위의 프로세스들을 관리하는 역할을 한다.