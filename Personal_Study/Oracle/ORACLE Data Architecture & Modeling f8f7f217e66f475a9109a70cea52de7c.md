# ORACLE Data Architecture & Modeling

# 소개

- 본문
    - 객체 관계형 데이터베이스 모델 : 역순목록(Inverted List), 계층적(Hirearchical), 네트워크(Network) 데이터베이스 모델의 다음 모델인 관계형 데이터 모델에서 객체 지향적 특징을 추가한 모델
    - 객체 관계형 데이터베이스 모델은 관계형 데이터 모델에서 메소드를 활용하는 방법론을 추가한 모델.
    - DB의 사용 목적은 동시발생 사용자들 사이에서 공유 네트워크를 관리하며(locking), 다수 사용자 사이의 생산성 최대화를 위해 컴퓨터 자원을 관리하며, 정전 등의 문제로 인한 데이터 손실을 방지하는데 있다.
    - DB를 통해 대용량 관리, 동시 접속 관리, 일관된 가용성을 얻을 수 있다..
    - 파티셔닝은 장점과 단점이 있다.
        
        <aside>
        💡 - 퍼포먼스(performance) : 다양한 파티션에 페러렐로 액세스함으로써 퍼포먼스
        를 분산할 수 있다.
        - 가용성(availability) : 디스크 고장으로 하나이상의 고립된 파티션에 있는
        데이터가 사용 불능이 되어도 손상되지 않은 파티션에 여전히 액세스 할 수
        있다.
        - 관리 용이성(manageability) : 테이블의 파티션은 개별 저장 영역이기 때문
        에, DBA는 백업과 복구를 지향하는 보다 효율적인 관리 운영이 될 수 있도록
        자체적으로 독립적인 관리가 가능하다.
        
        </aside>
        
        <aside>
        💡    - 거대한 테이블에 대한 전체 검색을 요구할 경우 수많은 데이터 블록을 읽는
             동안 애플리케이션과 시스템의 퍼포먼스는 방치된다.
           - 거대한 테이블이 있는 데이터블럭이 디스크 고장으로 인하여 단 하나라도
             액세스할 수 없게 되면 그 테이블을 전부 사용할 수 없으며, 전체를 다시
             복구해야 한다. (파티셔닝 문제)
        
        </aside>
        
    - 페러렐 프로세싱(Parallel Processing)을 통해 DML작업을 병렬로 관리해준다.
    - 페러렐 서버(Oracle Parallel Server: OPS) : 하나의 서버에서 다수의 기계 노드 사이에서 물리적으로 하나의 데이터베이스에 대한 엑세스 메커니즘을 지원한다.
        
        <aside>
        💡 ☞ 분산 락 관리자(distributed lock manager)를 통하여 데이터베이스 업데이트를
        조정
        ☞ 리버스 키(reverse key) 인덱스로 노드 사이의 블록 마찰을 제거
        ☞ 사용자 접속을 자동으로 고장난 노드에서 완전한 노드로 이전하여 애플리케이션
        에 대해 가용성과 연결성을 계속 제공한다.
        
        </aside>
        
    - 백업과 복구(Backup and Recovery)
        
        <aside>
        💡 ☞ Incremental 백업 : ORACLE8에서 추가된 기능으로 가장 최근의 백업 이후 바뀌
        어진 데이터 블록만 백업하기 때문에 백업 시간과 크기를 최소화한다.
        ☞ Point-In-Time 복구 : Point-In-Time(특정시간 복구)는 특정시점으로 완전한 데
        이터베이스를 복구할 수 있다.
        
        </aside>
        
    - 분산 데이터베이스 : 2개 이상으로 DB가 나뉘어있는 데이터베이스 (클러스터링)
    - 분산 트랜잭션 : Prepare → Commit 단계. (Prepare 단계에서 다수 트랜잭션의 Commit 확인 후 Commit 시행)
    - 테이블 스냅샷
    - 멀티 마스터 복제 : 다수 트랜잭션에서 하나의 데이터에 다수 트랜잭션의 접근이 필요할 때 공유 가능한 테이블(멀티 마스터)을 복제하는 행위. 뷰와 유사한 읽기 전용 테이블
    - 스냅샷 : 위와 유사

# 원리

- 본문
    - 스키마는 데이터베이스 객체를 물리적으로 체계화하는 것이 아니고, 논리적으로
    체계화하는 것이다
    - 데이터 사전(Data Dictionary) : 유일한 스키마, 데이터베이스의 정보를 요약한 정보
        
        <aside>
        💡 ☞ 오라클은 많은 시스템 테이블과 뷰 그리고 다른 객체를 이용하여 메타데이터
        (데이터베이스에 있는 데이터 자체에 대한 데이터)를 처리한다. 이를 데이터
        사전 또는 카탈로그라고 한다.
        ☞ 오라클은 SYS 스키마안에서 데이터 사전을 체계화한다
        
        </aside>
        
    - CHAR형은 공백으로 저장된 버퍼 공간을 SELECT 시 체크하지 않는다. VARCHAR는 확인한다. 따라서 고정 길이의 경우 CHAR형이 SELECT 시 유리하다.
    - BFILE : 데이터베이스 외부 파일 시스템으로 관리하는 LOB에 파일 포인터를
    저장한다
    - 디폴트 열 값 : 테이블에 대한 열을 선언할 때 해당 디폴트 열 값도 선언할 수 있다. (선언하지 않을 경우 NULL값 저장 )
    - 유형화된(materialized) 뷰
        
        <aside>
        💡 유형화된 뷰 원리(어떤 규칙)를 통하여 서버가 뷰를 통해 정확하게 기초 테이블
        로 삽입, 업데이트, 또는 삭제할 수 있도록 보증한다. 따라서 업데이트 가능한
        뷰를 정의하려면 이의 원리에 맞게 정의해야 한다.
        
        </aside>
        
    - B-TREE INDEX 구조는 INDEX키를 키값으로 데이터를 프로퍼티로 하는 트리 구조로 이루어진다. 트리 구조는 최상위 노드인 루트 블록에서 최하위 노드 리프 블록으로 이진 탐색 구조로 정렬 및 탐색한다.
    - Bitmap Index 구조는 성별과 같은 이진 구조에 적합한 컬럼에 대한 인덱스 값으로 사용할 때 유용하다.
    - 디스크 I/O를 줄이는 방법으론 디스크 사이의 버퍼 크기를 늘려 디스크 사이의 I/O에 걸리는 시간을 줄이는 방법 등이 있다.
    - 데이터 클러스터 : 데이터클러스터를 이용하는 목적은 하나이상의 테이블에 있는 공통적으로 사용되는 행을 물리적으로 같은 위치에 저장함으로써 디스크 I/O를 줄여 퍼포먼스
    를 향상시키기 위한 것이다.

# ORACLE DATABASE 구조

- 엑세스 제어
    - 주요 시스템 권한 목록
        
        
        | 권한 | 설명 |
        | --- | --- |
        | CREATE SESSION | 데이터베이스 서버에 접속할 수있고 세션을 확립할 수 있는 권한 |
        | CREATE TABLE | 자신의 스키마에서 테이블을 만들 수 있는 권한 |
        | CREATE ANY TABLE | 데이터베이스 임의의 스키마에서 테이블을 만들 수 있는 권한 |
        | CREATE ANY TYPE | 데이터베이스 임의의 스키마에서 TYPE과 관련 BODY를 만들 수 있는 권한 |
        | SELECT ANY TABLE | 데이터베이스내에 있는 테이블을 쿼리할 수 있는 권한 |
        | EXECUTE ANY PROCEDURE | 데이터베이스내에 있는 저장 프로시저와 내장함수, 패키지등의 구성요소를 실행할 수 있는 권한 |
        | EXECUTE ANY TYPE | 데이터베이스내에 있는 TYPE의 METHOD을 참조 및 실행할 수 있는 권한 |
    - 객체 권한(Object Privileges) : 테이블, 뷰 또는 저장 프로시저와 같은 특정 데이터베이스 객체에 대한 특정한 타입의 운영을 수행할 수 있는 권한
    - 역할(ROLE)에 따른 권한 부여
        
        
        | ROLE | 설  명 |
        | --- | --- |
        | CONNECT | 관련 스키마에서 테이블, 뷰, 시너님, 시권스, 데이터베이스링크, 데이터 클러스터를 만들 수 있도록하는 기본 사용자 역할 |
        | RESOURCE | 전형적인 애플리케이션 개발자를 위한 것으로, 관련 스키마에서 테이블, 시권스, 데이터 클러스터, 프로시저, 함수, 패키지, 트리거와 객체 타입을 만들 수 있는 권한 |
        | DBA | 데이터베이스 관리자를 위한 것으로 모든 시스템 권한이 포함되어 있다. |
        | SELECT_CATALOG_ROLE | 관리자 데이터 사전 뷰(Administrator data dictionary Views)를 쿼리할 수 있는 권한 |
        | DELETE_CATALOG_ROLE | 감사 트레일(Audit trail)에서 레코드를 삭제할 수 있는 권한 |
        | EXECUTE_CATALOG_ROLE | DBMS 유틸리티 패키지를 수행할 수 있는 권한 |
        | EXP_FULL_DATABASE,
        IMP_FULL_DATABASE | Export와 Import 유틸리티를 이용하여 데이터베이스 정보를 export, import할 수 있는 권한 |
    - 감사 옵션 설정 요령
        
        
        | 내용 | 명령문 |
        | --- | --- |
        | 사용자 SROGERS와 IGIBBS가 SELECT ANY TABLE이라는 역할(권한)을 이용하여 실패한 명령을 시도 횟수에 상관없이 세션별로 감사기록을 생성하도록 함 | AUDIT SELECT ANY TABLE
              BY srogers, igibbs
              BY SESSION
              WHENEVER NOT SUCCESSFUL; |
        | SALES.CUSTOMERS 테이블에 대한 SELECT 문을 모두 기록하도록 함 | AUDIT SELECT ON sales.customers
              BY ACCESS; |
        | 위의 감사를 취소할 경우 | NOAUDIT SELECT ON sales.customers; |
    - 트러스트 된 오라클 : 정부기관 요청으로 특수하게 납푸된 제품. 일반적인 오라클보다 보완이 강화된 제품
- 데이터베이스 스토리지
    - 논리적 스토리지 구조는 데이터베이스나 테이블 같은 데이터의 개념상의 조직
    이다.
    - 물리적 스토리지 구조는 파일이나 데이터 블록 같은 데이터 스토리지의 실체적
    인 단위이다.
    - 테이블스페이스는 디스크에 있는 하나 이상의 물리 데이터 파일에 해당되는
    오라클의 논리적인 조직.
    - 하나의 물리 데이터 파일이 다수의 테이블 스페이스를 공유할 수는 없다. (하나의 테이블 스테이스를 다수의 물리 데이터 파일이 공유하는 것은 가능)
    - 하나의 테이블 스페이스 안에 다수의 Segement가 있다.
    - System TableSpace (종류)
        
        <aside>
        💡 ○ 반드시 존재하는 테이블스페이스이다. 데이터베이스를 만들 때 SYSTEM 테이블스페이스의 구성 파일들의 이름과 크기 그외 다른 특징들을 정의해야 한다.
        
        ○ 데이터베이스의 데이터 사전을 SYSTEM 테이블스페이스에 보관한다.
        
        ○ 저장 프로시저와 함수, 패키지, 데이터베이스 트리거 그리고 객체타입 메소드와
        같은 모든 PL/SQL 프로그램에 대한 소스와 편집된 소스를 보관한다.
        
        ○ 뷰, 객체 타입 명세, 시너님 그리고 시퀀스 같은 데이터베이스 객체들은 어떤
        데이터도 저장하지 않는 단순한 정의이다. 이런 객체 정의를 SYSTEM 테이블스페
        이스에 있는 데이터사전에 저장한다.
        
        ○오라클 데이터베이스에 있는 모든 데이터와 인덱스 세그먼트에는 하나 이상의
        데이터블록 프리 리스트가 있다.
        
        </aside>
        
    - 사용자 정의 테이블 스페이스 (종류)
        
        <aside>
        💡 ○ SYSTEM 테이블스페이스에 있는 내부 데이터 사전 정보의 애플리케이션 데이터
        
        ○ 애플리케이션의 테이블 데이터와 인덱스 데이터
        
        ○ 내부 시스템 프로세싱 동안 사용된 임시 데이터
        
        ○ 데이터베이스의 체계화를 위하여 테이블과 인덱스의 스트리지를 분리하는 다수
        의 테이블 스페이스를 만드는 것이 좋다.
        
        ○ 활동적인 애플리케이션 데이터는 자주 백업되고 그렇지 않은 데이터는 자주
        백업되지 않으므로 이들을 다른 테이블 스페이스에 놓는 것도 좋다.
        
        </aside>
        
    - 온라인 및 오프라인 테이블 스페이스 (상태)
        
        <aside>
        💡 ○ 데이터의 가용성을 테이블스페이스별로 제어할 수 있다.
        
        ○ 온라인 테이블스페이스에 있는 데이터는 애플리케이션과 데이터베이스에서 유용 하다.(대개의 경우는 온라인 상태)
        
        ○ 오프라인 테이블스페이스에 있는 데이터는 애플리케이션과 데이터베이스에서
        유용하지 않다. 테이블스페이스에 문제가 있을 경우, 또는 일반적으로 필요없는
        히스토리 데이터가 있으면 관리자는 이 테이블스페이스를 오프라인하여 애플리
        케이션 데이터로의 액세스를 차단할 수 있다.
        
        ○ SYSTEM 테이블스페이스는 언제나 온라인이어야 한다.
        
        </aside>
        
    - 영구 테이블스페이스와 임시 테이블스페이스 (상태)
        
        <aside>
        💡 ○ 일반적으로 테이블스페이스는 영구적인 성격이나 임시 테이블스페이스는 분류된 쿼리와 조인, 인덱스 구축과 같은 복잡한 SQL 작업을 처리하는데 트랜잭션이
        사용할 수 있는 거대한 임시 작업 공간이다.
        
        ○ 영구 테이블스페이스에서는 많은 작은 임시 세그먼트들을 비효율적으로 만들고
        없애기 때문에 임시 테이블스페이스를 만듬으로써 보다 SQL 문장에 대해 임시
        작업 영역을 신속하게 제공할 수 있다.
        
        </aside>
        
    - 읽기 전용 및 읽기 쓰기 테이블 스페이스 (옵션)
        
        <aside>
        💡 ○ 테이블스페이스의 데이터가 결코 변하지 않을 때, 이를 Read-Only로 만들 수
        있다. 이렇게 하면 애플리케이션의 수정으로부터 보호할 수 있고 백업할 경우
        이는 백업할 필요가 없기 때문에 시간을 절약할 수 있다.
        
        ○ Read-Only와 Read-Write 모드는 필요시 항상 바꿀 수 있다.
        
        </aside>
        
    - UNIX O/S 상에서는 오라클은 스토리지 장치를 데이터 파일처럼 사용할 수 있다.
    → 단, 이 떄는 File I/O System이 지원되지 않아 구현이 어렵다. 
    → 또한 데이터 파일의 크기를 확장하는 것은 좋지 않다.
    - 물리 데이터 파일에 손상이 있을 때, 물리 데이터 파일과 테이블 스페이스 간의 Connection을 offline으로 돌린다.
    - 물리 데이터 파일간의 위치는 물리 디스크의 스토리지 구획 차이.
    - 제어 파일(Control File) :
        
        <aside>
        💡 물리적 구조에 대한 정보가 들어 있다.
        
        ☞ 데이터베이스의 이름, 데이터 파일의 이름과 위치
        
        ☞ 테이블스페이스에 대한 정보
        
        ☞ 시스템 백업을 포함한 현재의 물리적인 상태
        
        ☞ 기타 내부 정보
        
        </aside>
        
    - 오라클 데이터베이스는 자체 제어 파일이 없으면 적절히 작용할 수 없다. 분리된 디스크 고장이 일어날 경우 데이터베이스 가용성을 보장하기 위해서는 제어파일을 여러 위치에 미러링 하는 것이 좋다.
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled.png)
        
    - 테이블이나 데이터 클러스터를 만들면 데이터 세그먼트가 만들어지고 인덱스를
    만들면 인덱스 세그먼트가 만들어진다
    - 오라클은 SQL문에 대한 계산, 결과값들을 보관하기 위해 테이블 스페이스를
    사용한다. 이 때 임시적으로 할당되는 세그먼트를 임시 세그먼트(Temporary
    Segments)라 한다.
    - ROLLBACK SEGMENTS : 트랜잭션 롤백에 대비하려면 데이터베이스는 트랜잭션이 커미트나 롤백할 때까지 변경한 데이터를 기억하고 있어야 한다. 이런 데이터를 일시적으로(트랜잭션이 일단락 될 때까지) 보관하는 곳
    - 롤백 세그먼트는 extents 사이클이다. 트랜잭션이 롤백 세그먼트의 현재 extents 에 쓰기하여 결국 이를 롤백 정보로 가득 채우면 다음 extent에 계속해서 롤백 정보를 기록한다
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%201.png)
        
    - 롤백 세그먼트의 공간이 부족할 경우, 트랜잭션이 강제 취소되고 롤백된다.
    - 롤백 세그먼트는 다수가 아닌 하나의 세그먼트만 사용 가능하다. SAVEPOINT의 경우, 다수의 세그먼트가 아닌, 세그먼트의 특정 지점을 SAVEPOINT로 지정한다.
    - 다수의 트랜잭션이 하나의 롤백 세그먼트를 공유하는 것은 가능하다.
    - public 롤백 세그먼트는 오라클이 자동으로 액세스를 얻고 온라인을 가져오는
    롤백 세그먼트이다.
    - private 롤백 세그먼트는 서버 파라미터 파일(init.ora)에서 명시적으로 private 롤백 세그먼트의 이름을 나열한 때만 오라클 서버는 private 롤백 세그먼트를 획득한다. 이는 OPS(Oracle Parallel Server) 서버 옵션을 사용할 경우에 유용하다.
    - Deferred 롤백 세그먼트
        
        <aside>
        💡 ○ 디스크 문제로 오라클이 하나 이상의 데이터 파일을 오프라인으로 할 때, 애플
        리케이션에서 파일 액세스 에러를 눈치채지 못하도록 보통은 관련 테이블스페
        이스를 오프라인으로 한다. 이 경우 오라클은 SYSTEM 테이블스페이스 안에 연기
        된(deffered) 롤백 세그먼트를 만든다.
        
        ○ 연기된 롤백 세그먼트에는 손상된 오프라인 테이블스페이스에 적용할 수 없었던
        롤백 정보를 포함한다.
        
        ○ 손상된 테이블스페이스를 복구하여 온라인으로 되돌렸을 때 오라클이 그 테이블
        스페이스에 영향을 주었던 트랜잭션을 롤백하고 나머지 데이터와 일치되게 만들
        게 된다.
        
        </aside>
        
    - 데이터 블록(Data Blocks)
        
        <aside>
        💡 ○ 데이터 블록은 오라클 데이터베이스용 디스크 액세스 단위이다. 검색할 때,
        데이터 블록을 이용하여 디스크에 있는 데이터를 검색하고 저장한다.
        
        ○ 예컨데, 한 쿼리에서 요구한 결과 세트의 행들을 포함한 블록들을 메모리로
        올리게 된다.
        
        ○ 데이터베이스를 만들 때, 그 데이터베이스가 사용하게 될 블록 크기를 지정할
        수 있다. 데이터베이스 블록은 운영체제 블록 크기와 같거나 배수여야 한다.
        
        </aside>
        
    - 오라클 데이터베이스에 있는 모든 데이터와 인덱스 세그먼트에는 하나 이상의
    데이터블록 프리 리스트가 있다.
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%202.png)
        
        <aside>
        💡 ☞ 블록이 비어 있을 때 -> 프리리스트에 해당 블록 등재
        ☞ 블록에 데이터가 저장되어 더 이상 데이터가 추가될 공간이 없을 때
        -> 프리리스트에서 해당 블록 삭제
        ☞ 블록에 있는 데이터를 삭제하여 추가할 수 있게 될 때
        -> 프리리스트에 해당 블록 등재
        
        </aside>
        
    - DataBlock에 데이터가 추가됨에 따라 DataBlock의 분할이 필요해진다. 단, 무한히 추가될 경우 ROW CHAINING현상이 일어날 수 있으니 PREE LIST의 저장공간에 상한선을 두는 방식으로 주의해야 한다.
    - DataBlock은 데이터가 확장된 이후 줄일 경우 데이터의 소실 위험이 있으며, 늘릴 경우 퍼포먼스의 저하가 있을 수 있다.
- 객체 스토리지 세팅
    - 테이블스페이스 지정
        
        ```sql
        CREATE PUBLIC ROLLBACK SEGMENT reseg10
          TABLESPACE rbseg;
        ```
        
    - extends 셋팅
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%203.png)
        
    - 초기값 500K (INITIAL 500K) → 그 이후 (500K가 가득 찼을 때) 500K (NEXT 500K) → 최소 1개부터 최대 10개까지 EXTENTS 할당 (MINEXTENTS 1 ~ MAXENTENTS 10) → 500K로 부족할 경우 50% 증가해서 할당 (PCTINCREASE 50) → 초기값이 부족할 경우 고정값 310K로 할당(OPTIMAL 310K)
    - Data Block Settings :
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%204.png)
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%205.png)
    
- 멀티미디어 데이터에 대한 유일한 데이터 스토리지
    - 데이터베이스에 있는 테이블에 LOB 데이터 형(CLOB, BLOB, NLOB, BFILE 등)이 있으면 오라클은 각 행이 있는 작은 로케이터(Locator-위치 포인터) 라인만 그 테이블에 저장한다.
    - 이를 통해 데이터의 스토리지를 다른 물리적 위치로 분산하여 디스크 경합을 감소시켜 전반적인 시스템 퍼포먼스를 향상시킬 수 있다.
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%206.png)
        
- 데이터 파티셔닝(Data Partitioning)
    - 거대한 테이블(그리고 인덱스)는 그들의 크기와 스토리지 특징 때문에 데이터베이스 시스템에서 여러가지 문제들을 야기하거나 확대할 수 있다.
        
        <aside>
        💡 ☞ 사용 가능한 시간보다 시스템 내의 관리 작업을 수행하는 데 시간이 더 오래
        걸릴 수 있다.
        ☞ 완전한 검색(Full Scan)을 하는 경우 오라클이 테이블의 수 많은 블록을 읽고
        있는 동안 애플리케이션의 퍼포먼스는 방치된다.
        ☞ Mission Critical(시스템에서 필수)한 애플리케이션은 기본적으로 거대한 싱글테이블에 의존한다.
        그 테이블의 단 하나의 블록이 디스크 고장으로 인하여 액세스 할 수 없을 때,
        그 테이블은 사용할 수 없게 된다.
        
        </aside>
        
    - 파티션된 테이블(Partitioned Tables)
        
        <aside>
        💡 ○ 오라클은 거대한 테이블이 야기할 수 있는 많은 문제들을 해결하기 위한 하나의
        방안으로 테이블의 스토리지를 파티션이라는 보다 작은 스토리지 단위로 분리한
        다.
        
        ○ 각 테이블의 파티션에는 서로 다른 물리적인 속성(별도의 테이블스페이스,
        디스크 등)이 있다.
        
        ○ 각 파티션에는 다른 범위와 데이터블록 스트리지 세팅이 있다.
        
        ○ 행의 파티션 키 값은 오라클이 행을 저장할 파티션을 결정하는 기준이 된다.
        
        ○ 파티션 키 값이 업데이트되면 파티션사이의 마이그레이션이 발생하므로 이를
        방지하기 위하여 파티션 키 값은 결코 업데이트해선은 안된다.
        
        ○ 각 파티션에 대하여 배타적인 상위(High) 경계값을 지정하여 파티션에 대한
        데이터 범위를 결정할 수 있다. 각 파티션에는 암시적인 하위(Low) 경계값이
        있는 데 이는 그 이전 파티션의 상위 경계값이다.
        
        ```sql
        CREATE TABLE usa_customers
        			(id NUMBER(5) PRIMARY KEY,
        			  …………….
        			 state CHAR(2))
        		        PARTITION BY RANGE( state )
        			(PARTITION p1 VALUES LESS THAN ('H') TABLESPACE data01,
        			-- AL, AK, AZ, AR, CA, CO, CT, DC, DE, FL, GA
        			 PARTITION p2 VALUES LESS THAN ('MI') TABLESPACE data02,
        			-- HI, IA, ID, IL, IN, KS, KY, LA, MA, MD, ME
        			 PARTITION p3 VALUES LESS THAN ('NM') TABLESPACE data03,
        			-- MI, MN, MS, MO, MT, NC, ND, NE, NH, NJ
        			 PARTITION p4 VALUES LESS THAN ('S') TABLESPACE data04,
        			-- NM, NV, NY, OH, OK, OR, PA, PR, RI
        			 PARTITION p5 VALUES LESS THAN (MAXVALUE) TABLESPACE data05
        			-- SC, SD, TN, TX, UT, VA, VT, WA, WI, WV, WY
        ```
        
        ○ 상위 경계 MAXVALUE는 앞서의p4의 상위 경계보다 큰 값을 갖는 모든 경우를
        말한다(만약 파티션 키 값이 NULL이면 이 행은 p5에 보관된다).
        
        </aside>
        
    - 분할된 인덱스(Partitioned Indexes)
        
        <aside>
        💡 ○ 오라클은 클러스터되지 않은 테이블을 위한 범위로 분할된 인덱스를 지원한다.
        
        ○ 인덱스의 파티션 키는 오라클이 인덱스 엔트리를 저장하는 파티션을 결정하는
        기준으로 파티션 키에는 그 인덱스를 정의하는 하나 이상의 열이 포함되어 있어
        야 한다. 
        
        ```sql
        CREATE INDEX usa_customers_state ON usa_customers(state)
        		        PARTITION BY RANGE( state )
        			(PARTITION p1 VALUES LESS THAN ('H') TABLESPACE data01,
        			-- AL, AK, AZ, AR, CA, CO, CT, DC, DE, FL, GA
        			 PARTITION p2 VALUES LESS THAN ('MI') TABLESPACE data02,
        			-- HI, IA, ID, IL, IN, KS, KY, LA, MA, MD, ME
        			 PARTITION p3 VALUES LESS THAN ('NM') TABLESPACE data03,
        			-- MI, MN, MS, MO, MT, NC, ND, NE, NH, NJ
        			 PARTITION p4 VALUES LESS THAN ('S') TABLESPACE data04,
        			-- NM, NV, NY, OH, OK, OR, PA, PR, RI
        			 PARTITION p5 VALUES LESS THAN (MAXVALUE) TABLESPACE data05
        			-- SC, SD, TN, TX, UT, VA, VT, WA, WI, WV, WY
        			);
        ```
        
        ○ 분할된 테이블이라하여 항상 분할된 인덱스를 만들 필요는 없다. 인덱스 자체가
        파티션을 정당화할 만큼 클 때만 분할된 테이블의 인덱스를 분할하는 것이 당연
        하다.
        
        </aside>
        
    - Equi-Partitioned Objects : 두가지 이상의 서로 다른 객체들이 동일한 논리적 분할 속성을 가졌을 때, 이들을 Equi-Partitioned Objects라 한다. (앞의 USA_CUSTOMERS 테이블과USA_CUSTOMERS_STATE 인덱스) 이는 여러 상황에서 유익하게 사용될 수 있다.
    - 마스터 테이블(기준 테이블)과 그 세부 테이블(기준에 대한 하위 속성 테이블)을 동일하게 분할하는 경우 모든 마스터-디테일 행이 동일한 파티션에 있기 때문에 두 테이블의 조인을 매우 빨리 완수할 수 있다
    - 테이블과 그것의 인덱스를 동일하게 분할하는 경우(local partitioned index)
        
        <aside>
        💡 ☞ 특정 파티션 부분만 데이터가 업데이트된 경우 특성 파티션의 인덱스에만 영향
        을 미친다.
        ☞ 테이블과 인덱스가 동일하게 분류되지 않았다면 파티션 유지 관리 작업 과다로
        인하여 둘 이상의 인덱스 파티션을 사용하기가 어렵다.
        ☞ Local Partitioned Index의 생성시에는 범위 명세와 최대 인덱스 값을 표시할
        필요가 없다.
        
        </aside>
        
    - Global Partitioned Indexes : 자신의 테이블과 동일하지 않게 분할된 인덱스이다. 이는 인덱스 프로브(probe) 전체 수를 최소화 할 수 있기 때문에 유리할 수도 있으나 테이블의 파티션 키
    값의 변경이 해당 인덱스의 많은 혹은 전체 파티션에 영향을 줄 수도 있음을 명심해야 한다.
    - Partition-Extended Table Names : 분할된 테이블용으로 최적인 SQL 문장을 선언하기 위하여 테이블의 특정 파티션을 지정할 수 있도록 강력한 선언문을 지원한다.
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%207.png)
        
    - 파티션의 관리
        
        <aside>
        💡 ☞ 분할되지 않은 테이블을 분할된 테이블로 전환할 수 있으며, 반대도 가능함.
        ☞ 테이블의 기존 파티션 다음에 파티션을 추가할 수 있음.
        ☞ 테이블 중앙에서 분할하거나 합체 가능.
        ☞ 어떤 행도 포함하지 않는 테이블의 파티션을 삭제할 수 있음.
        ☞ 각 파티션을 TRUNCATE 할 수 있음.
        
        </aside>
        

# ORACLE Software Architecture

- Background Process
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%208.png)
    
    - DBWR(Database Writer Process) : 메모리에 있는 변경된 데이터 블록을 데이터베이스의 데이터 파일에 기록한다
    - LGWR(Log Writer Process) : Commit한 모든 변화에 관한 정보를 기록한다.
        - Oracle은 LGWR이 transction의 redo Entry와 Commit recode를 transction log에 성공적으로 기록할 때까지는 transction을 'Commit'된 것으로 생각하지 않는다
    - ARCH(Archiver Process) : LGWR이 redo 엔트리를 채운 후 ARCH는 이 로그 파일들을 자동으로 백업한다.
        - transction 로그 아카이브는 Oracle의 선택적인 특징이다. 그러므로 ARCH 프로세스는 이 특징을 사용할 때만 존재한다
    - CKPT(Checkpoint Process) : DBWR은 주기적으로 체크 포인터를 수행한다. 이 동안 DBWR은 메모리에 있는 모든 변경된 블록들을 데이터베이스의 데이터 파일에 기록한다
        - Database Instance는 LGWR를 덜어 주기 위하여, 체크 포인트 동안 데이터 파일 헤더를 갱신하는 데 전용으로 쓰이는 특별한 체크 포인트 프로세스(CKPT)를 시작한다
    - SMON(System Monitor Process) : SMON background process는 데이터베이스 비정상 종료 후 재 시작될 때 Redo Log File의 로그 정보를 읽어서 데이터 파일에 기록되지 않은 이전에 Commit된 데이터를 복구해 주게 된다. 이를 Instance 복구라 한다.
        - SMON Background Process는 이와 같이 사용하지 않는 데이터 블록들 중 구분선에 의해 끊어진 공간으로 인식하는 데이터 블록들을 하나의 공간으로 통합시킨다. 이를 데이터 블록과 연속된 공간의 통합이라고 한다.
        - SMON Background Process는 프로세스는 과거 버전에서 사용하던 Rollback Segments의 OPTIMAL 크기를 유지하기 위해 12시간에 한번 Rollback Segments를 축소하는 작업을 수행하게 된다. 이를 Temporary Segments 제거라고 한다.
        - OPTIMAL의 크기를 유지한다.
    - PMON(Process Monitor Process) : PMON은 못쓰게 된 Session의 transction을 Rollback하여 방치된 접속을 지워 버리는 역할을 한다
    - Dedicated/Multithreaded Background Processes
- Server
    - 전용 서버(Dedicated Server Architecture)
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%209.png)
        
    - 멀티스레드 서버 (Multithreaded Server Architecture)
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2010.png)
        
        <aside>
        💡 ○ 디스패처(Dispatcher) 프로세스는 클라이언트의 요청을 받아 그것을 서버의
        큐에 놓고, 또한 요청에 대한 결과를 적당한 클라이언트에 리턴한다.
        
        ○ 이런 아키택처에서는 지원하려고 하는 모든 네트워크 프로토콜(TCP/IP, IPX/SPX,
        DecNet)에 대해 적어도 하나의 디스패처 프로세스를 시작해야 한다.
        
        ○ 공유 서버 프로세스는 서버의 요청 큐에서 발견하여 해당 결과를 응답 큐로
        리턴하는 요청을 실행한다.
        
        </aside>
        
    - 싱글 테스크 서버 (Single-Task Server Architecture)
        - VAX/VMS와 같은 호스트 기반의 운영체제 하에서만 가능하고 모든 UNIX 서버는 이를 지원하지 않는다
        - 단 하나의 프로세스가 애플리케이션의 클라이언트와 포그라운드 서버 포션을 둘 다 수행하는 구조이므로 호스트 기반 환경에 매우 효율적이다.
- 메모리 영역
    - Cache Hits : 애플리케이션이 요청한 데이터가 Cache(메모리)에 있을 때 발생
    - Cache Misses : 애플리케이션이 요청한 데이터가 Cache(메모리)에 없을 때 발생하며 디스크 읽기가 발생한다.
    - Cache Reload) : 애플리케이션이 과거 Cache에 위치했었으나 그 이후 디스크로 내려간 데이터를 요청할 때 발생한다. 다시 디스크 읽기가 발생한다
    - MRU/LRU(Most-recently-used/Least-recently-used) 알고리즘 : 가장 최근에 사용된 Data Block은 Cache에 넣어 두고, 거의 최근에 사용되지 않은 블록은 더 많은 공간이 필요할 때 다시 디스크에 기록하는 Cache알고리즘
    - Buffer Cache : 오라클의 가장 큰 메모리 영역이다. 버퍼 캐시는 애플리케이션이 최근에 요청
    했던 데이터베이스 데이터를 저장한다
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2011.png)
        
        - Buffer Cache 크기를 산출하기 위해서는 서버를 시작하기 전에, 서버 파라미터를
        설정한 다음 Cache Miss와 Reload에 대한 버퍼 Cache Hit ratio 분석한다.
    - 공유 풀(Shared Pool) : 공유 풀은 또 다른 주요 서버 메모리 영역으로 라이브러리 및 사전 캐시라는 두 가지 구성 요소가 있다.
        - 라이브러리 Cache와 공유 SQL : 가장 최근에 실행된 SQL 문장과 PL/SQL 프로그램의 Parsing표현(parsed representations)를 저장하고 공유한다
        - 사전 Cache : 또 다른 사용자가 똑 같은 명령문을 실행한다면, Oracle은 그 명령문을 다시 실행하는 데 필요한 똑 같은 단계를 수행하기 보다는 메모리에 이미 들어 있는 그 명령문을 공유함으로써 이에 필요한 Overhead를 줄일 수 있다.
    - 이상의 메모리 영억을 시스템 글로벌 영역(SGA - System Global Area)라 한다.
    이에 대응되는 개념으로 프로그램 글로벌 영역(PGA - Program Global Area)이라
    는 것이 있으며, 이는 Oracle이 각각의 클라이언트를 위하여 Session 정보등 비교
    적 적은 양의 정보를 보관하는 서버 메모리 영역이다.(Sort 영역은 PGA 영역에
    만들어 진다)
    
     
    

# 업무영역분석

- 개요
    - ISP(Information Startegic Planning), BAA(Business Area Analysis), BSD(Businedd System Design)
    - 절차
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2012.png)
        
    - 산출물
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2013.png)
        
    - FHD : Function Hierarchy Diagram
    - FDD : Feature-Driven Development
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2014.png)
        
    
- 논리적 데이터 모델링
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2015.png)
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2016.png)
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2017.png)
    
- 토대 구축
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2018.png)
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2019.png)
    
    - 유형 엔터티
        - 관찰자, 다른 엔터티와 독립적으로 존재
        - 궁극적으로 속성값으로 표현되는 관찰가능한 특성을 소지
        - 유일한 독자성을 보유
    - 개념 엔터티
        - 존재가 관찰자에 의존
        - 관찰자가 특성을 정의, 추상적
    - 엔터티 추출 방법
        - 엔터티에 관한 정보의 기록이 필요한가
        - 현재 혹은 가까운 기간에 필요한가
        - 업무에 정보를 제공하는가
        - 실질적으로 그 정보를 관리하는가
        - 다른 엔터티와 구분이 되는가
        - 엔터티를 구분하는 특성은 무엇인가
            
            ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2020.png)
            
            ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2021.png)
            
    - (실제로는 관계 추출, 관계 분류가 거의 동시에 진행된다.)
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2022.png)
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2023.png)
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2024.png)
        
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2025.png)
    
- 식별자 정의
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2026.png)
    
    - 식별자 : 엔터티의 특정 건(Occurrence)의 유일성을 보장해 주는 속성(Attribte) 또는 속성의 집합
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2027.png)
        
    - 외부키 : 두 엔터티간의 관계를 결정하는 속성
    - 참조무결성 규칙
        - 입력규칙 : 자식 엔터티의 건이 입력될 때 또는 외부키가 수정될 때 적용
            
            ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2028.png)
            
        - 삭제규칙 : 부모 엔터티의 건이 삭제될 때 적용
            
            ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2029.png)
            
        - 수정규칙 : 부모 엔터티의 관계에 대응하는 속성이 수정될 때 적용(기본키가 아닌 경우)
            
            ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2030.png)
            
- 데이터 모형 상세화
    
    ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2031.png)
    
    - 속성 : 엔터티에 통합되는 구체적인 데이터 항목으로 더 이상 분리될 수 없는 최소의 데이터 보관 단위
        - 정의 절차 : 속성 추출 → 속성 배치 → 속성 검증
    - 속성의 유형
        - 기초 속성 : 현업으로부터 제공되어야만 유지되는 기본적인 속성
        - 설계 속성 : 원래 존재하지 않지만 필요에 따라 설계자가 생성한 속성
        - 추출 속성 : 다른 속성으로부터 계산 등의 가공처리를 통해 만들어진 중복성을 지닌 속성
    - 도메인 : 속성이 가질 수 있는 값에 대한 업무적 제약요건으로부터 파악된 일련의 특성
    - 도메인 무결성
        - 유효값 리스트(Valid List) 중의 속성값만 유효
        - 특정 건의 다른 속성과의 무결성
        - 한 속성의 여러 건 사이의 무결성
    - 하부유형엔터티 : 동일 엔터티의 특정 건 중 상호 배타적인 속성 그룹을 하부유형으로 분류
    - 분할 의의
        - 추가적 속성과 관계를 적용하여 엔터티를 구분되도록 별도로 정의
        - 일반적인 데이터모델에서 비지니스규칙의 차이를 명확하게 데이터 모델에 표현
        - 상부유형의 속성이 값을 가지지 않을 경우 선택성 제거에 도움
        - 엔터티의 특정 건(Instance)이나 유형(Type)에만 연관된 관계를 명확히 설명
    - 범주 : 하나 이상의 상호 배타적인 하부유형
    - 범주 구분자(Category Discreminator) : 하부유형을 구분하는 속성, 상부유형에 위치
    - 속성업무규칙(Attribute Business Rule) : 도메인 무결성에 부가하여 입력, 삭제, 수정 또는 조회 등의 작업이 동일 엔터티나 다른 엔터티의 속성에 미치는 연쇄 작용(Tirggering Operation)에 관련된 업무규칙으로 데이터 무결성의 마지막 형태
        
        ![Untitled](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/Untitled%2032.png)
        

# 수업자료

[교육자료(Oracle Architecture 8i 기초)_김가은.doc](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/%25EA%25B5%2590%25EC%259C%25A1%25EC%259E%2590%25EB%25A3%258C(Oracle_Architecture_8i_%25EA%25B8%25B0%25EC%25B4%2588)_%25EA%25B9%2580%25EA%25B0%2580%25EC%259D%2580_(1).doc)

[교육교재(데이터모델링)_김가은.pptx](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/%25EA%25B5%2590%25EC%259C%25A1%25EA%25B5%2590%25EC%259E%25AC(%25EB%258D%25B0%25EC%259D%25B4%25ED%2584%25B0%25EB%25AA%25A8%25EB%258D%25B8%25EB%25A7%2581)_%25EA%25B9%2580%25EA%25B0%2580%25EC%259D%2580.pptx)

[ORACLE PLSQL3.0(Oracle 8.x).doc](ORACLE%20Data%20Architecture%20&%20Modeling%20f8f7f217e66f475a9109a70cea52de7c/ORACLE_PLSQL3.0(Oracle_8.x).doc)