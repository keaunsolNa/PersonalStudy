# Semaphore & Mutex

# Semaphore

- sudo code
    
    ```cpp
    struct semaphore {
        int count;
        queueType queue;
    };
    
    void semWait (semaphore s) {
        s.count--;
        if (s.count <= 0) {
        	// 락이 걸리고 공유 자원에 접근할 수 없음
        }
    } 
    
    void semSignal (semaphore s) {
        s.count++;
        if (s.count <= 0) {
        	// 아직 락에 걸려 대기중인 프로세스가 있음
        }
    }
    ```
    
- Semaphore는 액세스 제어 메커니즘에 사용되는 음수가 아닌 변수 값이다.
- 여러 프로세스 간에 정보를 공유하기 위한 동기화 도구로 사용된다.
- 리소스의 한정된 인스턴스가 사용 가능해질 때까지 여러 Process Thread가 리소스의 한정된 Instance에 액세스할 수 있다.
    - Semaphore 변수의 값은 리소스가 필요한 모든 Process에서 변경할 수 있지만, 한 번에 하나의 Process만 값을 변경할 수 있다.
- Semaphore는 Signaling 메커니즘으로 락을 걸지 않은 Thread도 Signal을 보내 lock을 해제할 수 있다.
    - Semaphore는 동기화를 위해 wait와 signal이라는 2개의 atomic operations를 사용한다. wait를 호출하면 semaphore의 카운트를 1 줄이고, semaphore의 카운트가 0보다 작거나 같아질 경우에 lock이 실행된다.
    - Semaphore의 카운트가 0보다 작거나 같아져 동기화가 실행된 상황에서, 다른 Thread가 signal 함수를 호출하면 Semaphore의 카운트가 1 증가하고, 해당 Thread는 lock에서 빠져나올 수 있다.
- Semaphore는 Counting Semaphores, Binary Semaphore 2종류가 있다.
    - Counting Semaphore는 카운트가 양의 정수값을 가지며, 설정한 값만큼 Thread를 허용하고 그 이상의 Thread가 자원에 접근하면 락이 실행된다.
    - Binary Semaphore는 카운트가 1이며 뮤텍스 처럼 사용될 수 있다

# Semaphore의 장단점

- 장점
    - Semaphore를 사용하면 시스템 리소스를 보다 효율적으로 할당할 수 있다. 이는 메모리를 보다 효율적으로 사용할 수 있음을 의미한다
    - Semaphore를 사용하면 여러 프로세스를 제어할 수 있다. 즉, 필요에 따라 특정 작업에 메모리를 할당할 수 있다.
    - Semaphore 기반 메모리 관리는 성능을 향상시키고 시스템 응답성을 향상시킨다.
    - Semaphore는 기계 독립적이며 마이크로커널에서 실행되어야 한다.
- 단점
    - Semaphore는 프로그래밍 오류가 발생하기 쉽다
    - 프로그래밍 Semaphore의 복잡성으로 인해 상호 배제가 불가능하다
    - Semaphore 구현은 메모리 및 CPU 사용 측면에서 비용이 많이 들 수 있다
    - 선 순위 반전 문제가 있다.
        - 우선 순위 반전 ⇒ 우선 순위가 낮은 Process는 Critical Section에 진입하고, 우선순위가 높은 Process는 계속 대기하는 현상이다.
    - 대규모 시스템에 사용할 수 없다.

# Mutex

- Mutual Exclusion Object
- 자원에 대한 접근을 동기화하기 위해 사용되는 상호배제 기술이다.
    - 잠금 기반 접근 방식을 사용한다.
- 한 번에 하나의 프로세스만 리소스에 액세스할 수 있도록 하는 데 사용된다.
    - 프로세스가 리소스를 사용할 때 리소스를 잠그고 사용한 다음 해제한다.
    - 동일한 Process가 잠금을 동시에 획득하고 해제할 수 있다.
    - Mutex 개체는 모든 Process가 동일한 리소스를 사용할 수 있도록 허용하지만 한 번에 한 Process에서 리소스에 액세스한다.
    - 여러 Process Thread가 공유 리소스에 액세스할 수 있지만 한 번에 하나만 액세스할 수 있다.
    - 1개의 lock만을 갖는 Locking 메커니즘으로 한 번에 하나의 Thread만이 동일한 시점에 뮤텍스를 얻어 Critical Section에 들어올 수 있다.
- 프로그램이 시작될 때(프로세스가 시스템에서 리소스를 요청할 때마다) 고유한 이름 또는 ID를 가진 뮤텍스 개체가 만들어진다.
    - Process가 해당 리소스를 사용하려고 할 때마다 개체에 대한 잠금을 획득한다.
    - 잠금 후 Process는 리소스를 사용하고 Mutex 개체를 해제한다.
- 상호 배제를 지원하는 Semaphore 유형으로, 두 Thread가 특정 리소스에서 동시에 작동하는 것을 방지한다.
- 뮤택스는 Semaphore처럼 사용될 수 없다.

# 뮤텍스의 장단점

- 장점
    - 뮤텍스는 서로 다른 두 Thread가 동시에 리소스에 액세스하는 것을 방지하는 장벽을 만드는 것으로, 다른 Thread가 리소스를 필요로 할 때 리소스를 사용할 수 없게 된다.
    - 뮤텍스는 코드 안정성에 도움이 될 수 있다. CPU의 메모리 관리가 실패하면 Thread가 액세스하는 리소스를 사용할 수 없게 될 수 있는데, 이 시점에서 리소스에 대한 액세스를 방지함으로써 시스템은 메모리 관리 실패를 유발하는 오류로부터 복구할 수 있으며, 여전히 리소스를 사용할 수 있게 도와준다.
    - 한 번에 하나의 Process만 Critical Section에 있기에 경쟁 조건이 발생하지 않는다.
    - 데이터는 일관성을 유지하며 무결성을 유지하는 데 도움이 된다.
- 단점
    - 획득한 Context 이외의 Context에 의해 잠기거나 잠금 해제될 수 없다
    - 일반적인 구현은 CPU 시간을 낭비하는 바쁜 대기 상태를 초래할 수 있다
    - Critical Section에서는 한 번에 하나의 Thread만 허용해야 한다.
    - 한 Thread가 잠금을 획득하거나 휴면 상태가 되거나 선점되면 다른 Thread가 더 이상 멈출 수 없다. 이는 starvation을 유발 할 수 있다
        - ⇒ Starvation, 기아 상태. 프로세스가 끊임없이 필요한 컴퓨터 자원을 가져오지 못 하는 상황

# Semaphore VS Mutex

| Semaphore | Mutex |
| --- | --- |
| 신호 메커니즘 | 잠금 및 잠금 해제 메커니즘 |
| 정수 변수 | 객체 |
| wiat, signal | Lock, Unlock |
| Counting Semaphores, Binary Semaphore 존재 | 하위 유형 없음 |
| Semaphore는 수정할 수 있는 두 개의 원자 연산(wait, signal)으로 작동한다. | 자원을 요청하거나 해제하는 프로세스에 의해서만 수정 가능 |
| Process에 리소스가 필요하고 사용 가능한 리소스가 없는 경우, Process는 Semaphore 값이 0보다 클 때 까지 대기 작업을 수행해야 한다. | 뮤텍스가 잠겨 있으면 Process는 Process Queue에서 대기해야 하며 뮤텍스는 잠금이 해제된 후에만 액세스할 수 있다. |