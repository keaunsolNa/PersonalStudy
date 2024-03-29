# Memory Allocation(Address Binding)

# 정의

- 메모리 할당은 컴퓨터 프로그램과 서비스에 물리적 또는 가상 메모리 공간을 할당하는 프로세스다.
- 메모리 할당은 주로 컴퓨터 하드웨어 레벨의 작업이지만 OS 및 소프트웨어 응용 프로그램을 통해 관리된다.
- 메모리 할당 프로세스는 물리적 메모리 관리와 가상 메모리 관리에서 매우 유사하다.
- 프로그램과 서비스는 실행될 때 요구 사항에 따라 특정 메모리가 할당된다.
- 프로그램이 작업을 완료하거나 유휴 상태가 되면 메모리가 해제되어 다른 프로그램에 할당되거나 기본 메모리 내에 병합된다.
- 메모리 할당은 실행 전이나 실행 시에 수행되며, 다음의 두 가지 유형이 있다.
    - 컴파일 시간 혹은 정적 메모리 할당
    - 런타임 혹은 동적 메모리 할당
- 정적, 동적 메모리 할당 모두 사용 가능한 메모리 위치와 사용 중인 메모리 위치를 나타내는 각 메모리 위치 블록을 유지해야 한다. 그 뒤 새 작업이 시스템에 들어오면 여유 파티션을 할당하는데, 이 때 사용하는 전략은 다음과 같다.
- Fit 전략
    - Worst Fit
        - 프로세스가 전체 메모리를 순회하며 항상 가장 큰 파티션/구멍을 검색한 뒤 해당 파티션/구멍에 프로세스를 배치한다.
        - 가장 느린 프로세스다.
    - First Fit
        - OS는 프로세스의 메모리 요청을 수용할 수 있을 만큼 큰 블록을 찾을 때까지 목록의 처음부터 시작하여 가능한 메모리 블록 목록을 검색한다.
        - 적합한 블록을 찾으면 OS는 블록을 할당 부분과 여유 블록으로 나눈다.
    - Best Fit
        - OS는 사용 가능한 메모리 블록 목록을 검색하여 프로세스의 메모리 요청에 가장 가까운 크기의 블록을 찾는다.
        - 적합한 블록을 찾으면 OS는 블록을 할당될 부분과 나머지 여유 블록으로 나눈다.
    - Next Fit
        - First Fit의 수정된 버전이다.
        - 빈 파티션을 찾기 위해 처음부터 시작하지만, 재 호출 시 처음이 아닌 중단된 부분부터 검색을 시작한다.
        - 로빙 포인터를 사용하며 포인터는 메모리 체인을 따라 이동하여 다음 맞춤을 검색한다.

# 정적 메모리 할당과 동적 메모리 할당

- 정적 메모리 할당
    - 컴파일러에 의해 선언된 변수에 할당된다.
    - 주소는 연산자의 주소를 사용하여 찾을 수 있으며 포인터에 할당할 수 있다.
    - 메모리는 컴파일 시간에 할당된다.
- 동적 메모리 할당
    - 실행 시 수행되는 메모리 할당
    - calloc() 및 malloc() 함수로 동적 메모리 할당을 지원한다. (C)
        - 메모리 공간의 동적 할당에서는 값이 함수에 의해 반환되고 포인터 변수에 할당될 때 이러한 함수를 사용하여 할당된다.
    
    | No | 정적 메모리 할당 | 동적 메모리 할당 |
    | --- | --- | --- |
    | 1 | 정적 메모리 할당에서는 프로그램이 실행되거나 함수 호출이 완료될 때까지 변수가 영구적으로 할당된다. | 동적 메모리 할당에서는 프로그램 단위가 활성화된 경우에만 변수가 할당된다. |
    | 2 | 정적 메모리 할당은 프로그램 실행 전에 수행된다. | 동적 메모리 할당은 프로그램 실행 중에 수행된다. |
    | 3 | 메모리의 정적 할당을 관리하기 위해 Stack을 사용한다. | 메모리의 동적 할당을 관리하기 위해 Heap을 사용한다. |
    | 4 | 동적에 비해 비효율적 | 정적에 비해 효율적 |
    | 5 | 정적 메모리 할당에는 메모리 재사용성이 없다. | 동적 메모리 할당에는 메모리 재사용성이 있으며 필요하지 않을 때 메모리를 해제할 수 있다. |
    | 6 | 정적 메모리 할당에서는 메모리가 할당되면 메모리 크기를 변경할 수 없다. | 동적 메모리 할당에서는 메모리가 할당될 때 메모리 크기가 변경될 수 있다. |
    | 7 | 사용되지 않은 메모리를 재사용할 수 없다. | 메모리를 재사용할 수 있다. 사용자는 필요할 때 더 많은 메모리를 할당할 수 있다. 또한 사용자는 필요할 때 메모리를 해제할 수 있다. |
    | 8 | 동적 메모리 할당보다 실행 속도가 빠르다. | 정적 메모리 할당보다 느리다. |
    | 9 | 이 메모리는 컴파일 시간에 할당된다. | 이 메모리에서는 런타임에 할당된다. |
    | 10 | 이 할당된 메모리에는 프로그램 시작부터 끝까지 남아 있다. | 이렇게 할당된 메모리는 프로그램 도중 언제든지 해제될 수 있다. |
    | 11 | 일반적으로 배열에 사용됩니다 . | 일반적으로 Linked List 에 사용된다 . |

# 참조

- C의 malloc()
    
    [Memory Allocation](https://samwho.dev/memory-allocation/)