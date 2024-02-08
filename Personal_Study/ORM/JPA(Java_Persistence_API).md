# JPA(Java Persistence API)

<aside>
💡 Persistence : 영속성. 
→ 데이터 지속성은 Application이 비휘발성 스토리지 시스템에서 정보를 지속하고 검색하는 수단. 
JPA는 기존의 DB와의 연관성에서 탈피하여 지속성 및 객체 관계형 매핑 및 기능을 관리하기 위한 메커니즘.

</aside>

# 개요

- 정의
    - JAP란 자바측에서 데이터를 영속적으로 관리하기 위한 API.
    - 자바 진영의 ORM(Object Relational Mapping)기술 표준으로 ORM기술을 사용하기 위한 표준 인터페이스의 모음.
    - ORM은 자바 객체와 DB테이블을 매핑하고 자바 객체간의 관계를 토대로 SQL을 생성 및 실행할 수 있으며 대중적인 언어에는 대부분 ORM 기술이 존재한다.
    - Hibernate를 사용한다.
    - JPA Application은 다음의 순서로 개발된다.
    1. Entity Manager 설정
    2. Transaction관리
    3. Business Logic
- 특징
    
    ![Untitled](JPA(Java_Persistence_API)/Untitled.png)
    
    - 영속성 컨텍스트가 엔티티를 생명주기를 통해 관리한다.
    - native SQL을 통해 직접 SQL을 해당 DB에 맞게 작성할 수 있다.
    - DBMS별로 dialect를 제공한다.
    - DBMS에 종속되지 않고 자바 객체만을 가지고 개발을 할 수 있다.
    - DB의 종류에 따른 인터페이스 방언과 라이브러리 등록이 필요하다.
    
    ![Untitled](JPA(Java_Persistence_API)/Untitled%201.png)
    
    ![Untitled](JPA(Java_Persistence_API)/Untitled%202.png)
    
    - 장점
        - 객체지향과 관계지향이라는 서로 다른 패러다임 불일치를 해소하며, SQL 중심이 아닌 객체지향 패러다임 중심의 개발이 가능하다.
        - SQL을 수정할 필요가 없으므로 설정 및 필드 변경시 SQL이 자동으로 수정된다.
        - DB의 종류에  따른 차이를 개발자가 아닌 JPA가 판단하고 해당 DB에 맞는 SQL을 작성해 준다.
        - 캐시를 활용한 성능 최적화(Persistence Context)로 인해 트랜잭션을 처리하는 시간이 굉장히 많이 단축된다.
    - 단점
        - 복잡한 SQL을 작성하기에 적합하지 않다.
        - 객체지향 패러다임과 관계형 데이터베이스 패러다임에 대한 높은 이해가 필요하다.
        - 동적 SQL같은 경우 순수 JPA만으로는 부족한 부분이 있어 추가 라이브러리 활용이 필요하다.
- MyBatis와 JPA
    - Mybatis는 SQL Mapper로 SQL Mapping을 사용하는 영속성(DB에 저장) 프레임워크다. 개발자가 직접 SQL 코드를 작성하고 객체에 대해 매핑을 위한 설정을 모두 직접 처리해야한다. 또한, 수정이 이루어질 시 SQL 뿐만 아니라 매핑 될 객체까지 같이 수정해야 하는 번거로움이 있다.
    - JPA는 ORM기술이며, Mybatis는 SQL Mapper의 한 종류로 그 분류가 다르다.
    - Application이 고도화될 수록 JPA보다 Mybatis가 더 편리할 수 있다.
- 동작 방식
    - Java Application과 JDBC 사이에서 동작한다. 내부적으로 JDBC API를 활용한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%203.png)
        
    - JAP는 엔티티를 저장하는 환경인 Persistence Context를 통해 엔티티를 보관하고 관리한다.

# Persistence Context

![Untitled](JPA(Java_Persistence_API)/Untitled%204.png)

- 정의
    - 자바에서 객체를 Persistence Context 공간에 넣으면 엔티티와 스냅샷을 비교하는 과정을 통해 JPA의 Persistence Context공간에서 해당 객체 인스턴스를 관리한다.
    - 이후 Persistence Context의 1차 캐시 공간에서 해당 객체를 관리하며, 자바에서의 setter를 활용한 객체 변경시 1차 캐시에서 해당 명령을 확인, DML 명령어를 작성하여 쓰기 지연 SQL 저장소에 DML Query를 작성하여 보낸다.
    - 이후, 쓰기 지연 SQL 저장소에서 Buffer 단위로 DML 명령어를 DB로 flush 한다. 이때, flush는 commit을 통해서도 자동으로 이루어진다.
    - 1차 캐시 :  Persistence Context 내부에 Map으로 관리되는 캐시(key는 @id이며 매핑한 식별자, value는 Entity Instence)이며, 1차 캐시 영역에 있는 Entity는 캐시에서 바로 불러오므로 조회 성능이 향상된다. (단, 최초 실행시에는 DB와의 연결이 필요하다.)
    - 1차 캐시에서의 Entity Instence는 반복 호출 시 동일성을 보장한다. 즉, 같은 Entity Instence를 가져온다.
- Life Cycle
    
    ![Untitled](JPA(Java_Persistence_API)/Untitled%205.png)
    
    - Entity에서 persist() 명령어를 통해 Java 객체가 Persistence Context에 저장된다. 해당 Entity를 Managed Entity라고 칭한다.
    - detach(), clear(), close()등의 명령어를 통해 DB에 영향을 주고 싶지 않은 Entity를 Detached Entity 공간으로 빼돌린다. 이후 DB에 직접적 영향을 주는 작업을 수행 후 Detached Entity의 객체를 merge()명령어를 통해 다시 Persistence context 공간에 다시 저장한다.
    - remove() 명령어를 통해 Persistence Context 영역에서 Removed Entity로 Managed Entity를 보낸다. (삭제). 이후 persist()를 통한 재등록이 필요하다.
    - find() 명령어를 통해 select, JPQL로 DB Entity를 조회할 수 있다. (단일 행은 select으로 가능하지만, 다중 행은 JPQL이 반드시 필요하다.)
    - flush를 통해 쓰기 지연 SQL 저장소에 담겨있는 객체들을 DB로 Commit한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%206.png)
        
    - 비영속((new/transient) : Entity를 통해 객체 생성 시에는 Persistence Context나 DB와는 관련 없는 상태.
    - 영속(managed) : Entity Manager가 객체를 Persistence Context에 저장하면 Persistence Context가 해당 객체를 관리하게 되며, 이를 영속 상태라고 칭한다. 
    → .find()나 JPQL을 사용한 조회도 해당 객체를 영속 상태로 만든다.
    → .persist(); 를 이용한다.
    - 준영속(detached) : Pwersistence Contexst가 관리하던 Entity를 관리하지 않는 상태로 뒀을 때를 칭한다. 준영속 상태인 것은 DB에 반영되지 않는다. 
    detach() : 특정 Entity만 준영속 상태로 만든다.
    .clear() : Persistence Context를 완전히 초기화
    .close() : Persistence Context를 종료
    - 삭제(removed) : Entity를 Persistence Context 및 DB에서 삭제한다.
    - 병합(merge) : 준영속 상태의 Entity를 받아서 해당 정보로 새로운 영속 상태의 Entity를 반환한다. 단, 준영속 상태의 Entity와 merge로 가져온 Entity는 서로 다른 객체임에 유의하자.
- Transactional write-behind
    - Transaction을 지원하는 쓰기 지연
    - Entity 등록(Insert)를 예로 들면, Entity Manager는 Transaction을 커밋하기 직전까지 DB에 저장(flush)하는 대신 쓰기 지연 SQL 저장소에 INSERT SQL문을 쌓아두게 된다. 이후 커밋 시에 해당 쿼리들을 DB로 보내는데 이를 트랜잭션을 지원하는 쓰기 지연이라고 한다.
    - Flush는 Persistence Context의 변경 내용을 DB에 반영한다.
    - Flush의 절차는 아래와 같다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%207.png)
        
    - 변경 감지(dirty checking) : SQL에 의존적이지 않도록 엔티티의 데이터 변경을 감지하고 데이터베이스에 자동으로 반영하는 기능.
    - Persistence Context에 보관할 때 최초 Entity 상태를 복사하여 저장한 SnapShot과 Entity를 비교하여 감지한다.
    - 영속 상태의 Entity에만 적용된다.
    - 변경 감지 절차는 아래와 같다. (커밋 실행시)
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%208.png)
        

# Entity Manager, Entity Manager Factory

- 정의 및 생성, 설정들.
    - Factroy 타입의 디자인 패턴을 사용한다.
    - Entity Manager Factory를 생성한 후 해당 Factory 객체를 통해 Entity Manager 객체를 생성한다. 이후, Entity Manager 객체를 통해 JPA의 CRUD 과정이 이루어진다.
    - Entity Manager Factory는 다른 Factory 객체들과 마찬가지로 Cost가 높기에, Private Static 변수로 만든 뒤 Template 타입으로 관리하는 방식이 유리하다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%209.png)
        
    - SqlSessionFactory 객체와 유사하게, 외부 리소스 파일에 Factory 객체를 만들기 위한 설계도를 마련하고, 해당 리소스 파일을 매개변수로 받아오는 형식으로 구현한다. (유지보수 관리)
        
        <aside>
        💡 해당 설계파일인 persistence.xml 파일을 인식하기 위해서는 classpath에 등록된 경로 아래의 META-INF 폴더 안에 작성해야 하는 점 유의
        
        </aside>
        
    - persistence.xml (”jpatest”)
        - mybatis의 mapper.xml의 namespace를 이용한 remix 방식 연결처럼, <persistence-unit name=””></persistence-unit> 태그를 활용하여 해당 리소스 파일을 불러올 Mapping Name을 설정한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2010.png)
            
        - 해당 태그 안에 <properties> </properties> 태그를 만들고 태그 안에 속성들을 작성한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2011.png)
            
        - javax.persistence로 시작하는 속성은 JPA 표준 속성으로 특정 구현체에 종속되지 않는다.
        - property태그의 name 속성으로 DB key값을, value=””로 벨류값을 지정하여 연결할 DB의 driver, user, password, url 등을 지정한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2012.png)
            
        - 사용하고자 하는 데이터베이스에 맞는 방언을 <property name=”” value=””> 태그를 활용하여 지정한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2013.png)
            
        - 위까지는 필수 속성이며, 이후로는 JPA 구현에 있어 선택적으로 사용할 수 있는 몇 가지 옵션들이다.
        - JPA는 별도의 Log처리를 하지 않아도 자동으로 Log처리가 이루어진다. 아래의 3가지 옵션은 log에 관련된 내용들이다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2014.png)
            
        - DB의 Sequence를 Java에서 JPA를 통해 생성하고, 관리할 수 있다. 아래의 속성을 통해 관리한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2015.png)
            
        - 단, 연결한 DB가 시퀸스를 지원한다면 SequenceGenerator를 사용하지만, 지원하지 않는다면 TableGenerator를 사용해야 한다.
        - 하나의 Project에서 동일한 name의 Entity가 있을 경우 충돌이 발생한다. 이 때 아래의 속성을 false로 한뒤, Entity Annotation에 name을 부여할 수 있다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2016.png)
            
        - 단, 이는 가능의 영역으로, Project 내에서는 동일한 이름의 Entity를 사용하지 않는 것이 당연히 좋다.
    - 단, Entity Manager의 경우 다수의 Thread간 공유 시 동시성 문제가 발생할 수 있기에 Thread 간 공유를 차단해야 한다.  즉, Singleton Patten이 강제된다. 
    → createEntityManager() 메서드를 통해 요청이 있을 때 마다 EntityManager 객체 생성. Connection객체와 그 활용이 유사하다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2017.png)
        
    - 이후 생성된 Entity Manager를 통해 DB와 CRUD 과정이 이루어지는 방식이다.

# Mapping

- 정의
    - 기존의 사용하던 DTO 객체와 유사한 형식을 가졌으나, 그 목적과 의미는 다르다. 둘을 구별하여 생각하자.
    - javax.persistence.[] 를 import 하여 사용한다.
    - Setter와 Getter 설정, toString @Override는 기존의 DTO 생성 방식과 동일하다.
- @Entity
    - @Entity : 
    
    테이블과 매핑할 Entity Class. 
    
    name 속성을 활용하여 해당 클래스의 Entity 이름을 지정할 수 있다. 
    
    name 속성을 부여하지 않을 경우 해당 클래스 이름을 그대로 사용한다.
    
    대소문자를 구분하여 인식하며 다른 패키지에 이름이 같은 Entity Class가 있다면  충돌
    (DuplicateMappingException)이 발생한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2018.png)
        
- @Table
    - @Table : 
    
    Entity 클래스에 매핑할 테이블 정보를 name 속성을 사용하여 매핑한다. 
    
    생략 시 클래스 이름으로 테이블과 매핑한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2019.png)
        
- @Id
    - @Id : 
    
    변경 불가능한 식별자이며, Entity 클래스의 해당 필드와 테이블의 기본키를 매핑한다. 
    
    이 때 해당 필드를 식별자 필드라고 한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2020.png)
        
- @Column
    
    ![Untitled](JPA(Java_Persistence_API)/Untitled%2021.png)
    
    - @Column :
    
    엔티티 클래스의 해당 필드와 테이블의 컬럼을 name 속성을 사용하여 매핑한다. 
    
    생략 시 필드명을 사용하여 컬럼명과 매핑한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2022.png)
        
    - @Temporal : 
    
    Date 타입을 매핑할 때 사용한다. 
    
    @Temporal 사용을 위해서는 Date형이 java.util.Date형이어야만 한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2023.png)
        
    - @Lob :
    
    BLOB, CLOB타입을 매핑할 때 사용한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2024.png)
        
    - @Transient : 
    
    매핑하지 않을 특정 필드를 지정할 때 사용한다. 
    
    Java의 객체로서는 사용하고 싶지만, 해당 정보를 DB에 저장하고 싶지 않을 때 사용한다. 
    
    매핑이 이루어지지 않기에 저장 및 조회가 이루어지지 않고, DB에는 null값으로 Insert 된다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2025.png)
        
    - @Enumerated : 
    
    자바의 enum타입을 매핑할 때 사용한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2026.png)
        
- @Access
    - @Access : JPA가 엔티티에 접근하는 방식을 지정한다.
    - 클래스 레벨과 필드 레벨, Property 레벨에서 접근 방식 설정을 할 수 있다.
    - Class Level : 클레스 레벨에 @Access를 적용하여 모든 필드에 대한 설정을 적용할 수 있다. 
    
    Class Level에 적용시 모든 필드에 대해 적용되지만, Filed나 Method에 @Access를 같이 혼용 
    역시 가능하다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2027.png)
        
    - Field Level : 해당 필드의 접근 방식을 필드 접근으로 변경할 수 있다. 
    
    @Id가 있는 필드는 @Access(AccessType.FIELD) 효과를 낸다. (@Access 생략 가능)
    
    private 필드에 직접 접근한다. (getter를 사용하지 않는다.)
    
    ![Untitled](JPA(Java_Persistence_API)/Untitled%2028.png)
    
    - Property 레벨 : 해당 메소드로의 접근방식을 Property 접근으로 변경할 수 있다.
    
    @Id가 있는 메소드는 @Access(AccessType.PROPERTY)효과를 낸다. (@Access 생략 가능)
    
    private 필드에 직접 접근하지 않고, getter를 활용한다. getter로 값을 꺼내올 때 추가적인 작업을 원할 경우 주로 사용한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2029.png)
        
- @Enumerated
    - Java의 enum 클래스(enumeration Type)를 활용한 Entity 전략
    - Java의 배열과 유사하며 사실상 도메인의 성격을 지닌다.
    - 인덱스의 개념도 포함되어 있다.
    - 별도의 enum Type을 만들어 관리한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2030.png)
        
    - @Enumerated(EnumType.STRING) 을 통해 enum의 문자열을 그대로 사용할 수 있다.
    저장된 enum의 순서가 바뀌거나 enum이 추가되어도 안전하다.
    DB에 저장되는 데이터의 크기가 ORDINAL보다 크다.
    - @Enumerated(EnumType.ORDINAL)을 통해 enum에 정의된 순서대로 반환된 숫자값을 저장할 수 있다. 
    이미 저장된 enum의 경우 순서 변경이 까다롭다.
    DB에 저장되는 데이터의 크기가 작다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2031.png)
        
- Association Mapping
    - 정의
        - 객체 연관관계는 참조(주소)를 사용해서 맺고 테이블은 외래 키를 사용해서 관계를 맺는다.
        - 방향(Direction) : 
        
        방향은 단방향과 양방향이 있다. 
        
        객체 관계에서는 한번에 한 객체가 다른 한쪽을 참조하므로 단방향과 양방향이라는 개념이 따로 있다.
        
         메뉴 -> 카테고리, 카테고리 -> 메뉴이면 단방향으로 각각 참조하면서 서로가 서로를 참조하니 양방향이라고도 볼 수 있다.
        
        하지만 테이블 관계에서는 항상 양방향이다.(외래키를 통한 관계만 맺으면 서로 조인할 수 있으므로)
        - 다중성(Multiplicity) : 
        
        연관관계가 있는 객체 관계 혹은 테이블 관계에서 실제로 연관을 가지는(매핑되는) 객체의 수(객체 관계) 또는 행(테이블 관계)의 수에 따라 1:1, 1:N, N:1, N:N이라는 다중성을 가진다. (모델링의 Cardinality 개념)
        - 연관관계의 주인(Owner) : 
        
        외래 키의 관리자이며 객체를 양방향 연관 관계로 만들면 연관관계의 주인을 정해야 한다. 
        
        이는 단방향인 JAVA 객체 지향만의 개념이며, DB는 양방향이기에 해당하는 개념이 없다. 
        
        결국, FORIGEN KEY 제약 조건이 걸려있는 COLUMN이 있는 테이블이 주인(Owner)이며, 주체성을 가질 수 있느냐가 관건이다.
    - EmbeddedType
        - 새로운  값 타입을 직접 정의한 것으로 주로 기본 값 타입을 모아서 만든 하나의 타입을 말한다.
        - 엔티티의 필드중 일부분을 하나의 embedded Type으로 정의하면 식별성, 재사용성, 유지보수성이 높아진다.
        - Components라고도 부른다. (Entity의 Life Cycle에 의존하므로 Entity와 Embedded Type이 composition 관계이기 때문)
        - EmbeddedType은 Persistence Context가 관리하지 않으며, Entity도 아니다.
        - @Embeddable : 값 타입을 정의하기 위한 Annotation
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2032.png)
            
        - @EmbeddedId : 값 타입을 사용하는 곳에 적용하는 Annotation
        
        @Embeddable이 있는 EmbeddedType을 활용하는 것을 강조하는 의미가 있다. 
        
        EmbeddedType을 Entitiy와 연관시킨다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2033.png)
            
        - 복합키를 위한 Embedded Type 클래스에서 직렬화를 하지 않을 경우 
        Composite-id class must implement Serializable
        에러가 발생한다.
    - ManyToOne(N:1)
        - @ManyToOne : 다대일 관계라는 Mapping 정보이며, 다중성을 나타내는 Annotation을 필수로 사용해야 한다.
        - @JoinColumn : 조인 컬럼은 외래키를 매핑할 때 사용하며 매핑할 외래 키의 이름을 지정한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2034.png)
            
    - OneToMany(1:N)
        - @OneToMany : 1:N Mapping 정보이며 다중성을 나타내는 Annotation을 필수로 사용해야 한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2035.png)
            
    - ManyToMany(N:N)
        - DB 모델링에서는 지원되지 않는 개념으로, 객체 그래프 탐색 기법(ORM)에서 단방향 연관관계를 양방향으로 만들기 위해 사용한다.
        - 연관관계의 주인을 정하고 두 개의 단방향 연관관계를 양뱡향으로 만들기 위한 로직 관리의 필요성이 커진다.
        - 연관관계가 하나인 단방향 매핑에 주인이 아닌 연관관계를 하나 더 추가하는 방식으로 작성한다.
        - 단, 객체 관게에서도 양방향 연관관계라는 직접적인 관계 자체는 없음을 유의하자. 어디까지나 서로 다른 단방향 연관관계 2개를 로직으로 묶어 양방향처럼 보이게 하는 것이다.
        - @mappedBy : 양방향 연관관계에서 연관관계의 주인이 아닌 객체에 @mappedBy Annotation을 사용하여 연관관계의 주인 객체의 필드명을 매핑시키면 로직으로 양방향 관계를 적용할 수 있다.
    - 객체 그래프 탐색 기법
        - 객체를 통해 연관된 Entity를 조회하는 기법.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2036.png)
            
        - 출력문 :
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2037.png)
            

# Transaction

- 활용
    - EntityManagerFactory와 EntityManager 객체가 생성된 후, EntityManager 객체의 레퍼런스 변수를 활용한다.
    - EntityManager는 DB 연결이 꼭 필요한 시점까지 Connection을 얻지 않는다. Transaction이 시작될 때 Connection을 획득한다.
    - Transaction 처리를 위한 인터페이스 하위 구현객체 생성
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2038.png)
        
    - 앞서 구현한 EntityManager의 레퍼런스 변수의 getTransaction() method를 활용한다.
    - try-catch 구문을 활용한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2039.png)
        
    - Connection 객체와 마찬가지로, 비지니스 로직 절차 종료 후 반드시 종료하여 반환해야 한다.
- Business Logic
    - find()
        - find() : SQL의 SELECT와 유사하다. 단, 하나 이상의 조회는 JPQL이 필요하다.
        - Entity Manager에게 조회에 필요한 매개변수 값을 가지고 SELECT 요청을 한다.
        - 이후 Entity Manager가 Parsistence Context에게 해당 요청을 전달한다.
        - (최초 요청시) Parsistence Context는 DB에게 전달받은 요청을 전달하여 Quert를 수행한다.
        - (최초 요청시) Parsistence Context는 DB에게 전달받은 객체를 가지고 스냅샷을 만든다.
        - 이후 Client에게 전달한다.
        - (동일한 요청시) Persistence Context는 DB로 요청을 전달하지 않고 Persistence Context에 있는 스냅샷을 가지고 요청을 수행한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2040.png)
            
        - .find()의 매개변수로 조회할 객체의 하위 구현체와 parameter 전달
        - .find()는 DB에 있는 값이 아닌, Persistence Context에 있는 스냅샷을 가져온다. 즉, Update 후 .find()를 하나의 Transaction 단위에서 시행 시 .find()는 DB에 저장되어 있는 값이 아닌, setter로 변경된 Entity의 값을 getter 해 오는 것.
    - Update
        - Entity의 setter를 활용하여 객체의 변경시, JPA는 UPDATE 구문을 자동으로 생성한다.
        - 즉, Updata는 따로 Method가 존재하지 않는다.
        - 하나의 Update Query문으로 모든 Update 요청을 처리한다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2041.png)
            
        - 단, comiit 전 해당 Entity가 delete될 경우, Update문은 수행되지 않는다. 이는 UPDATE 문은 바로 DB로 전달되는 것이 아닌, Flush 과정 전까지 SQL 쓰기 지연 저장소에 Query가 쌓여있기 때문이다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2042.png)
            
    - .persist()
        - 생성된 Entity를 통해 비영속(new/transient) Context에 담겨있던 객체를 Persistence Context 영역으로 전달한다.
        - Persistence Context 영역에서의 작업 수행 이후, Commit 과정에서 Flush 과정이 이루어질 때 쓰기 지연 SQL 저장소에 담겨있던 Entity들은 DB로 저장된다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2043.png)
            
    - .remove
        - Persistence Context 영역에 있는 entity들을 지울 수 있다. 지워진 Entity는 Flush 과정 이후에도 DB에 반영되지 않는다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2044.png)
            

# Sequence

- 시퀸스 전략
    - DB에서 시퀸스 객체를 생성한다.
    - @SequenceGenerator를 사용하여 Sequence 생성기를 등록한다.
    - sequenceName 속성의 이름을 통해 실제 Sequence 객체와 Mapping한다. 그럴 경우 @Id(식별자 값)은 해당 SequenceGenerator가 할당하게 된다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2045.png)
        
    - name : SequenceGenerator의 이름
    - sequenceName : DB에 저장되어있는 Sequence의 이름
    - initialValue : DDL 첫 생성시 시작하게 될 값 (START WITH)
    - allocationSize : 시퀸스 호출 시 증가하는 수(기본값은 50이며, JPA는 DB Sequence에 접근하는 횟수를 줄이기 위해 한번에 Sequence값을 설정한만큼 증가시킨다) (INCREMENT BY)
- 테이블 전략
    - DB의 테이블을 사용해서 기본키를 생성한다.
    - Sequence가 지원되지 않는 DB에서 유용하다.(MySQL)
    - DB에서 테이블을 생성한다. (생략 가능)
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2046.png)
        
    - @TableGenerator를 사용하여 SequenceGenerator 등록
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2047.png)
        
    - name : SequenceGenerator의 이름
    - table : DB에 등록되어 있는 Sequence 생성용 테이블의 이름
    - pkColumnName : Sequence 컬럼명( 테이블 생성 생략으로 자동 생성됐을 경우의 기본값은 SEQUENCE_NAME)
    - valueColumnName : Sequence  값 컬럼명 (기본값은 NEXT_VAL)
    - pkColumnValue : 키로 사용할 값의 이름
    - sequenceName : DB에 저장되어있는 Sequence의 이름
    - allocationSize : 시퀸스 호출 시 증가하는 수(기본값은 50이며, JPA는 DB Sequence에 접근하는 횟수를 줄이기 위해 한번에 Sequence값을 설정한만큼 증가시킨다) (INCREMENT BY)
    - catalog, schema : DB의 catalog, schema 이름
    - uniqueConstraints(DDL) : 유니크 제약 조건 지정 시 사용
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2048.png)
        

# JPQL

- 정의
    - Entity 객체를 중심으로 개발할 수 있는 객체지향 Query
    - JPQL은 DBMS와 관계 없이 개발이 가능하다.
    → 방언을 통해 해결되며 해당 DBMS에 맞는 SQL실행, SQL을 추상화해서 특정 DB SQL에 의존하지 않는다.
    - JPQL은 find()메소드를 통한 조회와 다르게 항상 DB에 SQL을 싱행해서 결과를 조회한다. (쓰기 지연 SQL 저장소를 거치지 않는다.)
    
    → Persistence Context에 이미 존재하면 기존 Entity를 반환하고 조회한 것은 버린다. 즉, JPQL 실행 시 Persistence Context에서 비교처리를 하지 않고 바로 DB에서 ResultSet을 가져온 뒤, Persistence Context에 도착한다. 이 때 동일한 조회값이 있을 경우 조회해온 ResultSet을 버린다.
    - JPQL은 Entiity 객체를 대상으로 Query를 질의하고 SQL은 DB의 테이블을 대상으로 정의한다.
    - JPQL은 결국 SQL을 반환한다.
    - JPA의 공식 지원 기능으로는 
    Criteria Query : JPQL을 편하게 작성하도록 도와주는 API
    Native SQL : JPA에서 JPQL대신 직접 SQL을 사용할 수 있도록 하는 기능
    등이 있다.
    - JPA의 비공식 지원기능으로는
    QueryDSL : Criteria Query처럼 JPQL을 편하게 작성하도록 도와주는 Builder Class 모음 (비표준 오픈소스 프레임워크)
    JDBC 직접 사용 또는 Mybatis같은 SQL Mapper FrameWork : JDBC를 직접 작업해서 아용하는 기능 등이 있다.
    - Entity와 속성은 대소문자를 구분한다
    - SELECT, from과 같은 JPQL의 기본 키워드들은 대소문자를 구분하지 않는다. (그래도 관례상 대문자를 사용해서 구별하기 편하게 하자.)
    - 엔티티명은 클래스명이 아니라 엔티티명이다.
    - JPQL은 별칭을 필수로 사용해야 하며 별칭 없이 작성하면 에러(SQLSyntaxErrorException)가 발생한다.
- 활용
    - 작성한 JPQL(문자열)을 em.createQuery메소드를 통해 쿼리 객체로 만든다.
    - 쿼리 객체는 TypdeQuery와 Query 두가지가 있다.
    
    - TypedQuery: 반환할 타입을 명확하게 지정하는 방식일 때 사용(쿼리 객체의 메소드 실행결과로 지정한 타입이 반환 됨)
    
    - Query: 반환할 타입을 명확하게 지정할 수 없을 때 사용(쿼리 객체의 메소드 실행 결과로 Object 혹은 Object[]이 반환 됨)
    - 쿼리 객체에서 제공하는 메소드 getSingleResult() 혹은 getResultList()를 호출해서 쿼리를 실행하고 데이터베이스를 조회한다.
    
    getSingleResult(): 결과(행)가 정확히 하나일 때 사용(결과가 없거나 하나보다 많으면 예외가 발생한다.)
    
    getResultList(): 결과(행)가 2개 이상일 때 사용하며 컬렉션을 반환한다.(결과가 없으면 빈 컬렉션을 반환한다.)
        
        <aside>
        💡 getSingleResult()나 getResultList()는 Object를 반환하지만 조회 결과가 여러 스칼라값들의 모음일 경우 (Object[])로 다운 캐스팅 해야 한다.
        
        </aside>
        
    - 단일 행 조회 :
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2049.png)
        
    - 단일 행 다중 열 조회 :
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2050.png)
        
    - 다중 행 조회 :
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2051.png)
        
- 연산자
    - 우선 순위 및 종류
    1. 경로 탐색 연산(.)
    2. 수학 연산: +, - (단항 연산자), *, /, +, -
    3. 비교 연산: =, >, >=, <=, <>(다름은 =!나 =^는 적용 안됨),
    [NOT] BETWEEN, [NOT] LIKE, [NOT] IN,
    IS [NOT] NULL, IS [NOT] EMPTY, [NOT] MEMBER [OF], [NOT] EXISTS
    4. 논리 연산: NOT, AND, OR
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2052.png)
        
- Parameter Binding
    - named parameters
    → : 다음에 이름 기준 파라미터를 지정한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2053.png)
        
    - positional parameters
    → ? 다음에 값을 준다. 위치값은 1부터 시작한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2054.png)
        
- projection
    - Entity Projection
        - 원하는 객체를 바로 조회할 수 있다.
        - 조회된 Entity는 Persistence Context가 관리한다.
        - 단일 Entity만 가지고 진행 :
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2055.png)
            
        - 양방향 연관관계의 Entiry로 진행 :
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2056.png)
            
    - Embedded Projection
        - Entity와 거의 비슷하게 사용되며 조회의 시작점이 될 수 없다.
        - Entity Type이 아닌 값 타입으로 조회된 Embedded  Type은 Persistence Context가 관리하지 않는다.
        - Embedded Type의 경우 재사용성과 응집도가 유리하지만 Projection의 경우 유지보수성 측면에서 불리하다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2057.png)
            
    - Scalar Projection
        - 숫자, 문자, 날짜 같은 기본 데이터 타입이다.
        - Scalar Projection은 Persistence Context에서 관리되지 않는다.
        - 기본 데이터 타입 Projection :
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2058.png)
            
        - 다중열 Projection :
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2059.png)
            
    - new Projection
        - 다양한 종류의 단순 값들을 DTO로 바로 조회하는 방식으로 new 패키지명.DTO를 쓰면 해당 DTO로 바로 반환받을 수 있다.
        - new 명령어를 사용한 클래스의 객체는 Entity가 아니므로 Persistence Context에서 관리되지 않는다.
            
            ![Untitled](JPA(Java_Persistence_API)/Untitled%2060.png)
            
- CRUD Query
    - 기본적으로 Oracle Query와 그 구조는 거의 동일하다.  기존의 쿼리 대신 Entity 객체를 이용한다는 점이 다르다.
    - Select :
    - select_절
       from_절
       [where_절}
       {groupby_절}
       [having_절}
       {orderby_절}
    - insert : EntityManager가 제공하는 persist()메소드를 사용하면 완료된다.
    - update : 
    - update_절
       [where_절}
    - delete :
    - delete_절
       {where_절}
- Paging
    - JPA는 기존의 SQL과는 다르게 페이징 처리를 위한 별도의 API 객체를 제공한다. 
    .setFirstResult() : 조회를 시작할 위치(0부터 시작)
    .setMaxResults() : 조회할 데이터의 수 
    .getResultList() : 설정에 따른 결과집합
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2061.png)
        
    - 이후 for-each문을 통해 원하는 리스트를 출력해올 수 있다.
    - 생성된 쿼리 :
        
        ```jsx
        Hibernate: 
            /* SELECT
                m 
            FROM
                SECTION04_MENU m 
            ORDER BY
                m.code DESC */ select
                    * 
                from
                    ( select
                        row_.*,
                        rownum rownum_ 
                    from
                        ( select
                            menu0_.MENU_CODE as MENU_CODE1_2_,
                            menu0_.CATEGORY_CODE as CATEGORY_CODE2_2_,
                            menu0_.MENU_NAME as MENU_NAME3_2_,
                            menu0_.ORDERABLE_STATUS as ORDERABLE_STATUS4_2_,
                            menu0_.MENU_PRICE as MENU_PRICE5_2_ 
                        from
                            TBL_MENU menu0_ 
                        order by
                            menu0_.MENU_CODE DESC ) row_ 
                    where
                        rownum <= ?
                    ) 
                where
                    rownum_ > ?
        Menu [code=22, name=초코맛우동, price=0, categoryCode=6, orderableStatus=Y]
        Menu [code=21, name=돌미나리백설기, price=5000, categoryCode=11, orderableStatus=Y]
        Menu [code=20, name=마라깐쇼한라봉, price=22000, categoryCode=5, orderableStatus=Y]
        Menu [code=19, name=까나리코코넛쥬스, price=9000, categoryCode=9, orderableStatus=Y]
        Menu [code=18, name=붕어빵초밥, price=35000, categoryCode=6, orderableStatus=Y]
        ```
        
- GroupFunction
    - 함수는 기존의 SQL Query문과 다를 바가 없다. 아래의 몇 가지 주의사항만 조심하자.
    1. NULL값은 무시된다.
    2. DISTINCT를 집합 함수 안에 사용해서 중복된 값을 제거하고 구할 수 있다.
    3. DISTINCT를 COUNT에서 사용할 때는 임베디드 타입은 지원하지 않는다.
    4. 값이 없는 상태에서 COUNT를 제외한 그룹함수를 사용하면 NULL이 되고 COUNT만 0이 된다.
    - GROUP BY와 HAVING을 사용한 쿼리 :
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2062.png)
        
    - HAVING 절을 사용할 경우, WHERE절을 통한 조건문은 허용되지 않는다. HAVING절 사용 시 GROUP BY절만을 이용하여 조건을 설정하자.
- Join
    - JPQL JOIN도 SQL JOIN과 기능은 같으며, 문법상의 차이가 약간씩 있는 정도이다.
    1. 내부 조인(INNER JOIN)
    
    내부 조인은 INNER JOIN을 사용하지만 INNER는 SQL과 마찬가지로 생략 가능하다. 
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2063.png)
        
    2. 외부 조인(OUTER JOIN)
    
    외부 조인 역시 SQL과 마찬가지이기에 LEFT JOIN혹은 RIGHT JOIN을 적용할 수 있다. 
    
    내부 조인은 카테고리 중에 상위 카테고리에 해당하는 카테고리가 나오지 않았지만 외부 조인시 RIGHT JOIN을 활용하여 모든 결과값을 가져올 수 있다. 
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2064.png)
        
    3. 컬렉션 조인 (COLLECTION JOIN)
    
    컬렉션 조인은 의미상 분류된 것으로, SQL에서는 없는 Collection을 가지고 있는 Entity를 기준으로 조인하는 것을 의미한다. 
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2065.png)
        
    4. 세타 조인(THETA JOIN)
    
    WHERE 절을 활용하여 관계가 없는 Entity 끼리도 조인할 수 있다. 
    THETA JOIN은 내부 조인만 지원하며, SQL의 CROSS JOIN과 같다. 
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2066.png)
        
- SubQuery
    - JPQL도 SQL처럼 Sub Query를 지원한다. 하지만 SELECT, FROM절에서는 사용할 수 없으며 WHERE절과 HAVING절에서만 사용 가능하다.
    - WHERE절에 서브쿼리를 활용한 쿼리문 :
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2067.png)
        
    - 단일행 단일열의 결과가 나오는 Sub Query가 아니라면 SubQuery 함수를 활용해서 조회하는 과정이 필요하다. SubQuery함수는 SQl과 동일하며, 아래와 같다. 
    
    [NOT] EXISTS SubQuery
    
    {ALL | ANY | SOME} SuqQuery  
    
    [NOT] IN SubQuery
    - SubQuery를 활용한 SPQL문 :
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2068.png)
        
- namedQuery
    - .createNamedQuery() Method를 사용한다.
    - 동적 쿼리 : EntityManager가 제공하는 Method를 이용하여 JPQL을 문자열로 런타임 시점에 동적으로 쿼리를 만드는 방식. 동적 쿼리를 위한 조건식이나 반복문은 자바를 활용한다.
    - 정적 쿼리 : 미리 쿼리를 정의하고 변경하지 않고 사용하는 쿼리. 미리 정의된 쿼리는 Named Query라고 하며, Annotation 방식과 xml방식 두 가지가 있다. 쿼리가 복잡할 수록 xml방식이 선호된다. 
    
    Entity에 @NamedQuery() Annotation을 통해 정적 쿼리를 만들고 이름을 부여하는 방식으로 이루어진다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2069.png)
        
    - NamedQuery가 두 개 이상인 경우 아래의 형식으로 이루어진다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2070.png)
        
    - 이후 .createNamedQuery() 내장 Method를 이용, NamedQuery에 지정한 Query의 이름을 호출하는 방식으로 쿼리를 적용할 수 있다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2071.png)
        
    - 이 때,  Named Query가 Persistence Uint 단위로 관리되기에 충돌을 방지하기 위해 Entity의 class 이름으로부터의 확장자 형식으로 이름을 부여하자.
    - xml 방식으로도 구현할 수 있다. 전체적인 방식은 이전과 동일하다. 연산자가 있을 경우에는 <![CDATA[]]> 힁식을 유지하자.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2072.png)
        

# Native SQL

- 정의
    - .createNativeQuery() Method를 사용한다.
    - JPQL은 DB들이 따로 지원하는 것들에 있어 모든 것을 SQL로 자동 변경이 되지 않는다. 
    
    → (INLINE VIEW UNION, INTERSECT [Set Operator] 등등)
    - Native SQL은 SQL을 개발자가 직접 정의해서 사용할 수 있도록 해주는 수동모드이다. 
    
    즉, 어떠한 다양한 이유로 JPQL을 사용할 수 없는 경우나 SQL쿼리를 최적화해서 DB의 성능을 향상시킬 때 JPA는 Native SQL을 통해 SQL을 직접 사용할 수 있는 기능을 제공해 준다
    - JDBC API와의 차이점은 직접 SQL을 작성하는 JDBC API와는 달리 Native SQL은 JPA의 Persistence Context 기능을 그대로 사용할 수 있다.
    - Native Query API는 다음의 3가지가 있다.
    1. 결과 타입 정의 : public Query createnativeQuery(String sqlString, Class resultClass);
    2. 결과 타입을 정의할 수 없을 때 : public Query createNativeQuery(String sqlString);
    3. 결과 매핑을 사용 : public Query createNativeQuery(String sqlString, String resultSetMapping);
    
    - JPQL과의 차이점은 해당 DB 고유의 SQL문법을 사용한다는 것과 위치기반 파라미터만 지원한다는 것.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2073.png)
        
- Annotation 별 속성
    - @SqlResultSetmappin()의 하위 속성
    
    name : 결과 매핑 이름
    
    entities : @EntitiyResult를 사용해서 Entity를 결과로 매핑한다.
    → {}안에 여러 개의 @EntitiyResult 가능
    
    columns : @ColumnResult를 사용해서 컬럼을 결과로 매핑한다.
    → {}안에 여러 개의 @ColumnResult 가능
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2074.png)
        
    - @EntitiyResult()의 하위 속성
    
    name : 결과를 받을 필드명
    
    column: 결과 컬럼명
    - @FieldResult
    
    name : 결과를 받을 필드명
    
    column: 결과 컬럼명
    
    → @FieldResult를 사용해서 컬럼과 필드명을 수동으로 직접 연결할 경우 필드에 선언한 
        @Column Annotation이 없더라도 조회된 결과가 Entitiy Filed에 담기게 된다. 
    
    → @FieldResult를 한번이라도 선언하면 전체 필드들에 @FieldResult 설정을 해줘야 한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2075.png)
        
    - @ColumnResult
    
    name : 결과 컬럼명
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2076.png)
        
- 주의 사항
    - 조회할 값들이 Entitiy와 Scalar 값이 섞여 있는 복잡한 경우에는 @SqlResultSetMapping을 정의해서 결과 매핑을 직접적으로 사용해야 한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2077.png)
        

# Spring Data JPA

- 정의
    - Spring Framework에서 JPA를 편리하게 사용할 수 있도록 제공하는 Spring Data 모듈 중 하나.
    - JPA를 추상화(PSA)시킨 Repository라는 인터페이스를 사용하며, Query Method를 호출하는 것으로 손쉽게 SQL문을 생성할 수 있다.
    - Querydsl 쿼리 지원 및 이에 따른 안전한 JPA 쿼리를 처리해 준다.
    - pagination, dynamic query execution, ability to integrate custom data access code를 지원한다.
    - Spring Data Jpa를 사용하면 JPA에서 사용했던 기존의 EntityManagerFactory, EntityManager, EntityTransaction같은 객체가 필요 없다.
    - XML 기반의 Entity 매핑을 지원한다.
    - @EnableJpaRepositories을 도입하여 javaConfig 기반의 repository를 구성한다
    - @EnableJpaRepositories Annotation을 통한 Configuration 등록이 필요하다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2078.png)
        
        - XML 방식
            
            ```java
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans"
               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
               xmlns:jpa="http://www.springframework.org/schema/data/jpa"
               xsi:schemaLocation="http://www.springframework.org/schema/beans
                 https://www.springframework.org/schema/beans/spring-beans.xsd
                 http://www.springframework.org/schema/data/jpa
                 https://www.springframework.org/schema/data/jpa/spring-jpa.xsd">
            
               <jpa:repositories base-package="com.acme.repositories"/>
            
            </beans>
            ```
            
- Repository Interface
    - Repository Interface의 상속 구조는 다음과 같다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2079.png)
        
    - Repository : 특별한 기능은 없다.
    - CrudRepository : 주로 CRUD 기능을 제공한다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2080.png)
        
    - PagingAndSortingRepository : 검색 및 검색결과를 페이징 처리할 경우 사용한다.
    - JpaRepository : Persistnect Context flush 및 배치에서 레코드 삭제와 같은 일부 JPA 관련 추가 방법들을 제공한다.
    - Repository Interface의 주요 Method :
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2081.png)
        
- Query Method
    - Query Method(Parsing query method names)은 주제(subject)와 술어(predicate)로 나뉜다.
    - JPQL을 Method를 통해 대신 처리할 수 있도록 제공하는 기능.
    - Method의 이름으로 필요한 쿼리를 만들어주는 기능으로 네이밍 룰을 기반으로 사용한다.
    - Entity 이름을 생략하고 사용할 수 있다. 이는 해당 Repository Interface의 Generic에 해당하는 Entity를 자동으로 인식하는 기능이 있기 때문이다.
    - 주요 쿼리 메소드의 목록은 아래와 같다.
        
        ![Untitled](JPA(Java_Persistence_API)/Untitled%2082.png)