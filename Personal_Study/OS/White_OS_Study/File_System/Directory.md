# Directory

[Structures of Directory in Operating System - GeeksforGeeks](https://www.geeksforgeeks.org/structures-of-directory-in-operating-system/)

# Single-level directory

![Untitled](Directory/Untitled.png)

- 단일 레벨 디렉토리, 가장 간단한 디렉토리 구조다.
- 모든 파일이 동일한 디렉토리에 포함되어 있어 쉽게 지원하고 이해할 수 있다.
- 그러나 단일 수준 디렉토리에는 파일 수가 증가하거나 시스템에 두 명 이상의 사용자가 있는 경우 상당한 제한이 있다.
    - 모든 파일은 동일한 디렉토리에 있으므로 고유한 이름을 가져야 하며, 두 명의 사용자가 데이터 세트 테스트를 호출하면 고유 이름 규칙을 위반한 것이 된다.
    
    ### 장점
    
    - 단일 디렉토리이므로 구현이 쉽다
    - 파일 크기가 작을수록 검색 속도가 빠르다
    - 파일 생성, 검색, 삭제, 업데이트와 같은 작업이 쉽다
    - 논리적 구성
        - 디렉터리 구조는 파일과 디렉터리를 계층 구조로 논리적으로 구성하는 데 도움이 된다.
        - 이를 통해 파일을 쉽게 탐색하고 관리할 수 있어 사용자가 필요한 데이터에 더 쉽게 접근할 수 있다
    - 효율성 향상
        - 디렉터리 구조는 파일 탐색에 필요한 시간을 줄여 파일 시스템의 효율성을 높일 수 있다
        - 이는 디렉토리 구조가 빠른 파일 접근에 최적화되어 있어 사용자가 필요한 파일을 빠르게 찾을 수 있기 때문이다
    - 향상된 보안
        - 디렉터리 구조는 디렉터리 수준에서 접근을 제한함으로써 파일에 대한 보안을 강화할 수 있다
        - 이는 중요한 데이터에 대한 무단 접근을 방지하고 중요한 파일을 보호하는 데 도움이 된다
    - 백업 및 복구 촉진
        - 디렉터리 구조를 사용하면 시스템 오류나 데이터 손실이 발생한 경우 파일을 더 쉽게 백업하고 복구할 수 있다
        - 관련 파일을 동일한 디렉터리에 저장하면 보호해야 하는 모든 파일을 더 쉽게 찾고 백업할 수 있다
    - 확장성
        - 디렉터리 구조는 확장 가능하므로 필요에 따라 새 디렉터리와 파일을 쉽게 추가할 수 있다
        - 이는 시스템의 증가를 수용하는 데 도움이 되며 대량의 데이터를 더 쉽게 관리할 수 있게 해준다.
    
    ### 단점
    
    - 두 파일이 동일한 이름을 가질 수 있으므로 이름 충돌 가능성이 있다
    - 디렉토리가 크면 검색하는 데 시간이 걸린다
    - 동일한 유형의 파일을 그룹화할 수 없다

# Two-level directory

![Untitled](Directory/Untitled%201.png)

- 단일 수준 디렉터리의 문제점을 해결하기 위한 방법으로, 각 사용자는 고유한 UFD(사용자 파일 디렉터리)를 갖는 구조이다.
- UFD의 구조는 비슷하지만 각 UFD에는 단일 사용자의 파일만 나열된다.
- 새로운 사용자 ID가 생성될 때마다 시스템의 마스터 파일 디렉터리(MFD)가 검색된다.
    
    ### 장점
    
    - 동일한 이름을 가진 파일이 2개 이상 있을 수 있다는 점이며, 사용자가 여러 명인 경우 매우 유용하다
    - 사용자가 다른 사용자의 파일에 접근하는 것을 방지하는 보안이 있다
    - 파일 검색이 매우 수월하다
    
    ### 단점
    
    - 다른 사용자와 파일을 공유할 수 없다
    - 사용자는 하위 디렉터리를 만들 수 없다
    - 한 번의 사용으로 동일한 유형의 파일을 그룹화할 수 없으므로 확장성은 불가능하다

# Tree Structure / Hierarchical Structure

![Untitled](Directory/Untitled%202.png)

- 개인용 컴퓨터에서 가장 일반적으로 사용된다.
- 루트 디렉터리가 정점에 있는 거꾸로 된 실제 트리와 매우 유사한 구조를 가지고 있다
- 루트에는 각 사용자의 모든 디렉터리가 포함되어 있으며, 사용자는 하위 디렉터리를 만들고 해당 디렉터리에 파일을 저장할 수 있다
- 사용자는 루트 디렉터리 데이터에 대한 접근 권한이 없으며 이를 수정할 수 없다
- 루트 디렉터리에서도 사용자는 다른 사용자의 디렉터리에 접근할 수 없다
    
    ### 장점
    
    - 디렉터리 내부의 하위 디렉터리 구조를 허용한다
    - 검색이 더 쉬워진다
    - 중요한 파일과 중요하지 않은 파일의 정렬이 쉬워진다
    - 단일 레벨과 다중 레벨 디렉터리 구조보다 확장성이 뛰어나다
    
    ### 단점
    
    - 사용자는 다른 사용자의 디렉토리에 접근할 수 없으므로 사용자 간의 파일 공유가 방지된다.
    - 사용자는 하위 디렉터리를 만들 수 있으므로 하위 디렉터리 수가 늘어남에 따라 검색이 복잡해진다
    - 사용자는 루트 디렉터리 데이터를 수정할 수 없다
    - 파일이 하나의 디렉터리에 맞지 않으면 다른 디렉터리에 맞춰야 할 수도 있다

# Acyclic Graph Structure

![Untitled](Directory/Untitled%203.png)

- 한 디렉토리의 파일이 여러 디렉토리에서 접근될 수 있는 디렉터리 구조다.
- 비순환 그래프 방식으로 사용자 간 파일을 공유할 수 있으며, 여러 디렉터리가 링크를 통해 특정 디렉터리나 파일을 가리키는 방식으로 설계되었다
    
    ### 장점
    
    - 여러 사용자 간에 파일 및 디렉터리 공유가 허용된다
    - 검색이 매우 쉬워진다
    - 여러 사용자가 파일을 공유하고 편집할 수 있으므로 유연성이 향상된다
    
    ### 단점
    
    - 구조가 복잡하기에 디렉터리 구조를 구현하기 어렵다
    - 파일은 여러 사용자가 접근하므로 사용자는 파일을 편집하거나 삭제할 때 매우 주의해야 한다
    - 파일을 삭제해야 하는 경우, 파일을 영구적으로 삭제하려면 해당 파일의 모든 참조를 삭제해야 한다

# General Graph Structure

![Untitled](Directory/Untitled%204.png)

- 사용자는 OS에서 둘 이상의 상위 디렉토리의 도움을 받아 다양한 디렉토리를 파생할 수 있는 디렉토리 내에 디렉토리 순환을 생성할 수 있다
- 사용자는 동일한 구조 아래에 하위 디렉터리를 생성하는 동시에 루트 디렉터리 아래에 디렉터리를 자유롭게 생성할 수 있다.
- 파일 경로는 디렉터리 구조에서 파일을 찾기 위해 두 범주로 분류될 수 있다.
    - 절대 경로 : 루트 디렉터리를 기본 디렉터리로 간주하여 원하는 파일의 경로를 결정한다
    - 상대 경로 : 해당 디렉터리에서 검색해야 하는 파일이 기본 디렉터리로 간주되거나 사용자 디렉터리가 기본 디렉터리로 간주되는 두 가지 선택 사항에 의해 결정될 수 있다
    
    ### 장점
    
    - 디렉터리 내에서 디렉터리를 순환하거나 생성할 수 있다
    - 다른 디렉터리 구조에 비해 유연하다
    
    ### 단점
    
    - 디렉터리가 차지할 전체 크기 또는 공간을 계산하기 어렵다
    - 여러 하위 디렉터리를 생성할 수 있으므로 많은 가비지 수집이 필요해질 수 있다
    - 비용이 상대적으로 많이 소모된다.