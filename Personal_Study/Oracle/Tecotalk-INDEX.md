# Tecotalk - INDEX

- INDEX란?
    - (검색을 위해) 임의의 규칙대로 부여된, 임의의 대상을 가리키는 무언가
    - 순서는 상관 없다.
    - 한 테이블에 여러 개
    - 추가 저장 공간 필요 (약 10%)
    - Insert시 추가 작업이 필요하다. (인덱스 생성)
    - 카디널리티가 낮아질 수록 인덱스의 사용 필요성이 낮아진다.
- Clustered Index
    - 데이터와 인덱스가 군집화된 인덱스.
    - 순차에 따라 정렬되기에 범위 검색에는 강력하지만, INSERT 시에 취약점이 크다.
        
        ![Untitled](Tecotalk-INDEX/Untitled.png)
        
    - PK와 유사성이 크다. (Auto Increment)
- Non Clustered Index
    - Non-Clustered Index의 경우, 데이터와 인덱스가 순차적으로 이루어지지 않는다. 약한 참조관계로 이루어져 있으며 hasp방식으로 검색한다.
    - 또한 Clustered Index와 달리 순차적으로 이루어지지 않아도 무방하다.