# MyBatis

<aside>
💡 MyBatis의 핵심은 Mapping 구조.

</aside>

# 1. Framework의 개념

- framework와 library. 주도권
    - framework와 library의 가장 큰 차이는 주도권의 개념.
    - framework는 객체의 라이프 사이클에 대한 주도권을 framework가 가지고 있으며, library는 객체의 라이프 사이클에 대한 주도권을 개발자가 가지고 있는 API.
    - 그러나 framework 역시 하나의 library로 사용할 수 있기에, 둘의 구분은 명확히 정해져 있다고 보기엔 어렵다.
    - Framework: 개발자가 소프트웨어를 개발함에 있어 코드를 구현하는 개발 시간을 줄이고, 코드의 재사용성을 증가시키기 위해 일련의 클래스 묶음이나 뼈대, 틀을 제공하는 라이브러리를 구현해 놓은 것.
- Framework의 특징, 장단점
    - 개발자가 따라야 하는 가이드를 제공한다.
    - 개발할 수 있는 범위가 정해져 있다.
    - 개발자를 위한 다양한 도구, 플러그인을 제공한다.
        
        ![Untitled](MyBatis/Untitled.png)
        
- Framework의 종류
    
    ![Untitled](MyBatis/Untitled%201.png)
    
    - 영속성(persistence): RAM과 같은 휘발성 메모리 공간이 아닌, DISK 공간에 데이터를 저장하는 특성.
    SQL MAPPER(Mybatis) 계열과 ORM(Hibernate) 계열이 있다.
    → 이 중 Mybatis는 기존 MVC 구조에서 DAO파일에서 진행하던 xml파일과의 매핑 방식을 도와주고, Hibernate는 xml파일에서 작성하던 쿼리를 도와주는 엯할을 한다.

# 2. MyBatis

- 개념
    - 영속성 프레임워크 중 하나. 데이터의 CRUD를 보다 편하게 하기 위해 xml로 구조화한 Mapper 설정 파일을 통해 JDBC를 구현한 프레임워크.
    - 기존에 JDBC를 통해 구현했던 상당부분의 코드와 파라미터 설정 및 결과 매핑을 xml 설정을 통해 쉽게 구현할 수 있게 해준다.
- 클래스 인스턴스
    - SqlSessionFactoryBuilder
        - SqlSessionFactory 객체를 생성하기 위한 클래스 객체. SqlSessionFactory 클래스를 생성한 후 가비지 컬렉터가 제거한다. SqlSessionFactory를 다수 생성하기 위해 SqlSessionFactoryBuilder는 재사용될 수 있지만, 리소스 문제로 권장되지 않는다. 가장 좋은 방법은 하나의 SqlSessionFactory로 다수의 SqlSession을 생성, 관리하는 것이다.
            
            ![Untitled](MyBatis/Untitled%202.png)
            
    - SqlSessionFactory
        - SqlSession을 생성하기 위한 클래스 객체. 한 번 생성된 후 SqlSessionFactory는 애플리케이션을 실행하는 동안 계속 유지된다. SqlSessionFactory는 build 과정에서 소모되는 리소스가 크기에, 싱글톤 패턴이 권장된다. 즉, 하나의 SqlSessionFactory 인스턴스를 다수의 Session이 공유해서 사용하는 것.
            
            ![Untitled](MyBatis/Untitled%203.png)
            
        - if문을 활요한 싱글톤 패턴 구조의 SqlSessionFactory 생성
            
            ![Untitled](MyBatis/Untitled%204.png)
            
        - SqlSEssionFactoryBuilder()의 내장 메서드 build()는 InputStream타입을 매개변수로 받기에  유틸성 클래스 Resources를 이용, 환경변수가 담긴 xml파일을 InputStream 타입으로 변경하여 sqlSessionFactory를 생성한다.
    - SqlSession
        - DB와 JAVA 사이의 데이터를 이동하기 위한 객체로 PrepareStatement를 사용한다. 매 Session 은 Service 클래스에서 생성되어 DAO → Mapper.xml → DAO를 거친 뒤 Session에서 종료(close()) 된다. 별도의 설정을 통해 Statement를 사용할 수 있으나 권장되진 않는다.
            
            ![Untitled](MyBatis/Untitled%205.png)
            
    - Mapper 인스턴스(xml 방식)
        - xml 파일 형식으로 환경변수와 SqlSessionFactoryBuilder()에 대한 설정이 완료되었다면, DB와 통신할 쿼리문이 저장된 xml 파일(Mapper 인스턴스)를 DAO에서 호출하는 방식으로 DB와의 통신이 이루어진다. 이 때 Mapper 인스턴스의 호출은 Mapper 인스턴스에서 namespace로 지정한 클래스이름.쿼리이름(crud id)의 방식으로 이루어진다.
            
            ![Untitled](MyBatis/Untitled%206.png)
            
        - 이때 JDBC와는 달리, Mapper 인스턴스는 별도의 과정이 필요하다. 결과집합을 받기 위한 resultMap 지정과, DML 작업 시 PlaceHolder 의 사용법이 그 차이.
        - resultMap 지정은 Java와 DB를 오갈 정보를 담기 위한 DTO, Java Bean, VO의 key값과 DB의 실제 ColumnName을 매핑하는 과정이다. property를 이용한 key:value의 형식으로 이루어지며, 각각의 매핑에 대한 값을 Mapper 인스턴스의 키값(namespace)를 지정해주었던 것과 같이, Mapper 인스턴스 내부에서 쿼리문이 불러온 값을 JAVA단과 일치시키는 과정이다.
            
            ![Untitled](MyBatis/Untitled%207.png)
            
        - resultMap의 type 속성을 통해 DTO파일의 실제 위치를 지정하고, id 속성을 통해 쿼리문에서 불러올 때 사용할 key값을 지정했으며, 
        이후 PRIMARY KEY의 경우에는 <id property=”” column=””/>의 형식으로
        그 외의 컬럼은 <result property=”” column=””/>의 형식으로 각각 매핑했다.
        - 이후 쿼리문에서 resultMap이라는 속성으로 해당 resultMap의 키값을 불러오는 형식으로 사용된다.
        - 단, DML 작업의 경우, result 값은 INT 값이기에 resultType(resultMap) 을 적지 않는다.
        - ParameterType 사용시 주의사항 : Mapper 인스턴스에서 받는 parameter(placeHolder)의 경우, 별도의 parameterType 지정이 필요하다. 내장 별칭은 아래의 표 참고.
            - 내장 별칭
                
                ![Untitled](MyBatis/Untitled%208.png)
                
        
        ![Untitled](MyBatis/Untitled%209.png)
        
        <aside>
        💡 Placehorlder의 경우, #{  } 의 형식으로 사용한다. 이 때 중괄호 안에 들어가는 값은 DAO에서 Parameter로 던진 값이다.
        
        </aside>
        
        <aside>
        💡 Parameter 타입은 DTO(가독성 목적)와 HashMap(유지보수성 측면)만 가능하다.
        
        </aside>
        
    - Mapper 인스턴스(java Annotation 방식)
        - Java Annotation 방식의 경우 별도의 xml파일을 생성하지 않고, DAO 역할을 하는 interface를 통해 Mapper 인스턴스를 구현한다.
        - Service 계층에서 getMapper 내장 메서드를 통해 구현한  하위구현체(-interface)가 PrepareStatement와 SqlSession 정보를 가지고 DB와 통신한다.
        - 이때 하위 구현체 interface는 Annotation 기법을 통해 DB와 통신하기 위한 쿼리문과 ResultMap에 대한 정보를 담은 method에 각 CRUD에 맞는 Annotation을 통해 DB와 통신한다.
        - 주의해야 할 점은, 이미 하위구현체에 SqlSession에 대한 정보가 담겨있기에, 하위구현체의 method에서 parameter로 sqlSession을 넘길 필요가 없으며, parameterType(placehodler)가 필요한 DML 작업의 경우에는 오히려 에러를 발생시킨다.
            - Service 계층에서의 Mapper 인스턴스 하위 구현체 생성
                
                ![Untitled](MyBatis/Untitled%2010.png)
                
                - SqlSession 클래스의 레퍼런스 변수 sqlSession에 getSqlSession() 메서드 호출로 환경변수 설정값 지정 (sqlSessionFactory에서 생성한 SqlSession)
                - sqlSession 레퍼런스 변수의 내장 메서드 getMapper로 MenuMapper Mapper 인스턴스의 하위 구현체(menuMapper) 생성
                - 하위 구현체 menuMapper의 메서드 selectmenuByCode에 parameter로 전달받은 code를 매개변수로 호출, 그 결과값을 MenuDTO 자료형의 menu 레퍼런스 변수에 담는다.
                - sqlSession을 닫는다.
            - Mapper 인스턴스에서 ResultMap 지정
                
                ![Untitled](MyBatis/Untitled%2011.png)
                
                - ResultMap 지정은 xml방식과  큰 차이는 없다. 동일 클래스 안에서 사용되기에 type지정이 불필요하다는 점 정도. key값인 menuResultMap id값에 value값으로 parameter와 return값으로 받을 java 변수 이름과 DB의 column 이름을  key:value 형식으로 매핑하는 것.
            - Annotation 기법을 통한 쿼리문 생성
                - xml 파일에서 작성했던 쿼리문을 Mapper 인스턴스에서 구현한다. 구현 방식은 각 CRUD 과정에 맞는 Annotation의 매개변수로 쿼리문을 담는 형식이다.
                - Query문을 java단 언어로 표현하는 과정에서 코드가 더러워졌을 뿐, 그 형식은 xml방식과 차이가 없다.
                    
                    ![Untitled](MyBatis/Untitled%2012.png)
                    
    - Mapper 인스턴스(remix 방식)
        - Java Annotation 방식과 xml방식의 장점을 합한 형태. 쿼리문은 xml 파일에 저장하고, DAO역할의 Mapper 인스턴스 하위구현체에서는 메소드만을 남겨놓는다.
        - Service 계층에서 Mapper 인스턴스의 하위구현체를 생성하는 방식은 Java Annotation 방식과 동일하다.
            
            ![Untitled](MyBatis/Untitled%2013.png)
            
        - Mapper 인스턴스 하위구현체에는 쿼리문을 수행할 method만을 남겨놓는다.
        - xml 파일의 생성 위치와 방식이 중요하다. Mapper 인스턴스의 하위구현체와 동일한 패키지 구조하에 xml 파일을 생성하고, Mapper 인스턴스의 하위구현체의 풀네임을 xml 파일의 mapper namesspace로 지정해야 한다. (디렉토리 주소 포함)
            
            ![Untitled](MyBatis/Untitled%2014.png)
            
        - 이 때 Java에서의 인식을 위해 Java Build Path에서 Source 폴더로 생성한 Mapper 폴더를 지정하는 과정 역시 필요하며
            
            ![Untitled](MyBatis/Untitled%2015.png)
            
        - Mapper 인스턴스의 하위구현체에서 수행할 method의 풀네임과 xml 파일의 쿼리문의 id값이 동일해야 한다.
        - 마지막으로, 전달받는 parameter가 있는 경우(내장 별칭이 아닐 때), 해당 객체의 디렉토리 주소를 포함한 풀 패키지 네임이 필요하다.
            
            ![Untitled](MyBatis/Untitled%2016.png)
            
        - 그 외 resultMap 지정과 쿼리문 작성의 방식은 xml방식과 동일하다.
- 흐름
    
    ![Untitled](MyBatis/Untitled%2017.png)
    
    - DB 접속 환경 설정 및 연결 (Annotation 방식)
        - 환경 설정 및 하위 구현체 생성
            
            ![Untitled](MyBatis/Untitled%2018.png)
            
            - Environment class의  environment 객체에 
            
            환경설정한 객체를 호출하기 위한 String id 설정값 [”dev”]
            
            트랜잭션 매니저 방식 설정값 [TransactionFactory transactionFactory] 
            (jdbc 방식의 트랜잭션 매니저. ManagedTransactionFactory는 자동 처리 방식 트랜잭션 매니저)
            
            DataSource와 소스 저장 방식 설정값 [PooledDataSource, UnPooledDataSource] (ConnectionPool 사용방식. private static 변수로 설정한 DRIVER, URL, USER(DB의), PASSWORD(DB의) 값을 담는다.)
            을 지정한다.
            - DataSource 저장방식 참조
                
                ![Untitled](MyBatis/Untitled%2019.png)
                
            - DB 관련 static 변수 참조
                
                ![Untitled](MyBatis/Untitled%2020.png)
                
            - 이후 설정된 레퍼런스 변수 environment를 매개변수로 mybatis의 내장 객체 Configuration을 이용한 레퍼런스 변수에 환경 설정 정보를 저장한다.
                
                ![Untitled](MyBatis/Untitled%2021.png)
                
            - 이후 설정된 레퍼런스 변수 configuration에 Configuration class의 내장 메서드 addMapper를 이용, 쿼리문을 담은 인터페이스의 하위 구현체를 매개변수로 지정한다.
                
                ![Untitled](MyBatis/Untitled%2022.png)
                
                ![Untitled](MyBatis/Untitled%2023.png)
                
        - SqlSessionFactory 생성 및 Session 생성
            - 앞서 configuration 레퍼런스 변수에 SqlSessionFactory 인터페이스를 위해 필요한 모든 정보가 담겼으므로, 
            SqlSessionFactory 인터페이스 타입의 하위 구현 객체를 생성하기 위해 sqlSessionFactoryBuilder의  build(); 내장 메서드를 통해 생성한다. 
            이 때 build() 내장 메서드의 매개변수는 앞서 설정한 configuration 레퍼런스 변수다.
                
                ![Untitled](MyBatis/Untitled%2024.png)
                
            - Session 생성을 위한 인터페이스의 변수 설정까지 완료됐다면, 해당 레퍼런스 변수를 이용해 autocommit 설정을 지정할 수 있다.
                
                ![Untitled](MyBatis/Untitled%2025.png)
                
            - 이 때 매개변수가 true라면 auto commit 역시 true로 설정된다. 
            → false로 설정하여 커밋을 수동으로 설정하도록 하자.
    - DB 접속 환경 설정 및 연결 (xml 방식)
        - 환경 설정 및 resource mapper 설정
            - xml 파일을 생성한다. DOCTYPE은 Preferences의 User Specified Entries를 이용, Loacation과 Key값에 MyBatis의 CMD방식으로 구현한다.
                
                ![Untitled](MyBatis/Untitled%2026.png)
                
            - Location과 Key값 설정시, 해당 서식으로 xml 파일 생성시 DOCTYPE을 구현할 수 있다.  아래는 설정 후 신규 xml 파일 생성시 기초값.
                
                ![Untitled](MyBatis/Untitled%2027.png)
                
            - environment(s) 설정을 통해 default와 id를 지정한다. 영역은 <configuration> 태그 아래이며, <environments default=””>를 통해 환경변수 설정의 기본 값을 지정하고, <environment id=””>를 통해 호출할 환경변수의 id값을 지정한다.  (String id 설정값)
                
                ![Untitled](MyBatis/Untitled%2028.png)
                
            - <transactionManger type=””/> 태그를 통해 트랜잭션 매니저의 방식을 지정한다. JDBC와 MANAGED 둘 중 하나를 선택 가능하다.
                
                ![Untitled](MyBatis/Untitled%2029.png)
                
            - <dataSource type=””>
            <property name=”” value=””/>
            <dataSource> 
            태그를 통해 환경설정의 소스저장 방식을 설정한다. dataSource의 type 값으로는 POOLED와 UNPOLLED를 선택하며, <property name=””> 태그를 통해 driver와 url, 연결될 DB의 ID와 PASSWORD를 지정한다.
                
                ![Untitled](MyBatis/Untitled%2030.png)
                
            - 이후 호출할 query문이 담긴 xml 파일의 매핑 주소를 resource 변수명으로 지정한다.
                
                ![Untitled](MyBatis/Untitled%2031.png)
                
        - mapper query 설정
            - 앞서 환경 설정을 xml파일에서 location 을 지정한  mapper 파일 역시 xml 파일 형식으로 생성하며, 그 Location과 Key값은 MyBatis에서 CMD 형식으로 가져온다. 상세 방식은 아래와 같다.
                
                ![Untitled](MyBatis/Untitled%2032.png)
                
            - 생성된 mapper 파일의 초기값은 아래와 같다.
                
                ![Untitled](MyBatis/Untitled%2033.png)
                
            - mapper파일을 통해 실행할 쿼리문의 namespace와 id값을 지정한다. 이때 namespace값은 class파일, 쿼리문의 id값은 메서드와 같은 형태로 후에 호출된다.
                
                ![Untitled](MyBatis/Untitled%2034.png)
                
            - 이때 resultType을 통해 쿼리문으로 불러올 결과집합의 데이터타입을 지정할 수 있다.
        - xml 파일 호출 및 SQLSessionFactory 설정
            - Resources에 담길 환경변수 설정 값을 String 타입 변수값에 지정한다. 이 때 디렉토리 주소는 java build path 의 source 디렉토리가 기준이다.
                
                ![Untitled](MyBatis/Untitled%2035.png)
                
            - InputStream 추상 클래스를 통해 레퍼런스 변수에 Mybatis에서 제공하는 Resources 추상 클래스의 getResourceAsStream() 내장 메서드의 값을 담는다. 이 때 getResourceAsStream()의 매개변수는 앞서 설정한 resource 변수값(환경 변수 설정 값 디렉토리 주소)을 담는다.
                
                ![Untitled](MyBatis/Untitled%2036.png)
                
            - 설정된 레퍼런스 변수를 이용, SqlSessionFactory 추상클래스를 통해 레퍼런스 변수에 SqlSessionFactoy의 기본 생성자 설정값을 담는다. 이 때의 값은 build() 내장 메서드를 이용하며, 내장메서드의 매개변수 값은 앞서 설정한 InputStream의 레퍼런스변수다.
                
                ![Untitled](MyBatis/Untitled%2037.png)
                
            - 생성된 레퍼런스 변수를 이용하여 session값에 autoCommit여부를 설정한다.
                
                ![Untitled](MyBatis/Untitled%2038.png)
                
            - 세션 설정까지 완료되었다면, 앞서 mapper.xml 파일을 통해 설정한 쿼리문의 값을 “namespace.id” 형식의 매개변수로 생성된 session에 담는다.  내장 메서드는 CRUD 작업의 방식과 불러올 결과집합의 데이터 형식에 따른다. 에제는 하나의 값만을 가져왔기에 selectOne 내장 메서드를 사용했다.
                
                ![Untitled](MyBatis/Untitled%2039.png)
                
                - 제공되는 내장 메서드 참조.
                    
                    ![Untitled](MyBatis/Untitled%2040.png)
                    
- 동작 구조
    
    ![Untitled](MyBatis/Untitled%2041.png)
    
    - Session은 SQL Session.
    - Session Factory Builder에서 Session Factory를 만든다. 
    mybatis-config.xml파일은 일종의 설계도
    - Session Factory에서 Session을 만든다. (싱글톤 패턴구조)
    → mapper.xml 파일 은 SQL 쿼리문이 들어있다.
    - 전체적인 흐름은 mybatis-config.xml 파일을 참조하여 Session Factory Builder에서 Session Factory를 만든다. 이 때 Session Factory는 생성하는 일에 리소스 소모가 크기에 하나의 Session Factory만 만들도록 한다.
    - 생서된 Session Factory는 mapper.xml이라는 SQL query문을 참조하여 Session을 만든다. 이 때 Session은 내장 메서드인 selectOne, selsetList 등을 이용하여 기존의 DAO 파일에서 제공하던 작업을 수행한다.
- provide
    - Java Annotation 방식의 SQL 구문 작성의 번거로움을 간소화시킨 Annotation 방식 기법이다.
    - xml 파일을 별도로 생성하지 않고 java 파일로 관리하며, Annotation 기법으로 해당 쿼리를 불러오는 방식은 Java Annotation 기법과 동일하다.
    - DAO 계층의 역할을 하는 Mapper 파일에서 쿼리문이 담긴 java 파일을 불러오는 방식은 아래와 같다.
        
        ![Untitled](MyBatis/Untitled%2042.png)
        
    - type= 이라는 KEY값으로 쿼리문이 담긴 클래스 파일의 하위 구현체를 지정한다.
    - method=””라는 VALUE 값으로 해당 class 파일의 쿼리문이 담긴 method를 지정한다.
    - 불러온 쿼리문의 작성 방식은 아래와 같다.
        
        ![Untitled](MyBatis/Untitled%2043.png)
        
    - SQL 클래스의 인스턴스를 생성한다.
    - 해당 인스턴스의 내장 메서드들을 통해 각각의 CRUD 작업과 쿼리문을 참조연산자를 통해 sql 인스턴스 객체에 추가한다.
    - 이후 toString()을 통해 sql 인스턴스를 호출한 Mapper 파일로 반환한다.
        
        ![Untitled](MyBatis/Untitled%2044.png)
        

# 3. MyBatis 동적쿼리

- 개념 및 정의
    - 일반적으로 검색 기능이나 다중 입력 처리 등을 수행해야 할 경우 SQL을 실행하는 DAO를 여러 번 호출하거나, batch기능을 이용하여 버퍼에 담아서 한 번에 실행시키는 방식으로 쿼리를 구현했다면, Mybatis 동적쿼리는 쿼리문 안에서 조건문을 사용하는 방식으로 여러 개의 request에 대해 하나의 쿼리문으로 수행할 수 있게 도와주는 기능이다.
    - id와  parameterType, resultMap은 OGNL 표현식을 사용할 때를 제외하면 큰 차이는 없다. 그외의 SqlSession 생성과정 역시 마찬가지.
- if
    - 동적 쿼리를 사용할 때 가장 기본적으로 사용되는 구문으로, 특정 조건을 만족할 경우 <if> 태그 안의 구문을 쿼리에 포함시키는 개념이다. 필요 조건이 다수일 경우, 다중 if문 역시 지원한다.
        
        ![Untitled](MyBatis/Untitled%2045.png)
        
    - <if test=””></if> 형식으로 사용한다.
    - test를 통해 if문의 조건절을 규정한다. 위 4개의 test 값(조건절)는 parameter로 넘어온 price 값이 0<price≤10000일 때, 10000<price≤20000일 때, 20000<price≤30000일 때, 30000<price일 때로 나뉜 구문이다.
    - test를 통해 규정된 조건절이 true일 때, 해당 if문 태그 안에 있는 뭐리문을 <select></select>태그 안에 포함시키는 개념이다.
    - 따라서 위 쿼리문의 경우, parameter로 넘어온 price의 값이 250000일 때, WGERE 조건절의 쿼리문은 아래와 같다.
    WHERE A.ORDERABLE_STAUTS = ‘Y’ 
        AND A.MENU_PRICE BETWEEN 20001 AND 25000
        
        <aside>
        💡 xml 파일 안에서 <는 태그로 인식되기에, 비교연산자로 <를 쓰고 싶을 경우에는 <![CDATA[ QUERY ]]> 의 형식으로 태그 안의 내용이 태그가 아닌 문자열임을 명시해줘야 한다. (여기서는 쿼리문)
        
        </aside>
        
- choose
    - JAVA의 if-else, switch문과 유사하다. 주어진 구문 중 한 가지를 실행하고자 할 때 사용한다.
    - <choose><when></when>—-<otherwise></otherwise></choose>의 형식으로 사용한다.
    - <when> 태그의 test=”” 값으로 주어진 조건에 부합할 때, <when></when>태그 안의 내용을 쿼리문에 추가하며, 부합하는 조건이 없을 때는 <otherwise></otherwise> 태그 안의 내용을 쿼리문에 추가하는 형식이다.
        
        ![Untitled](MyBatis/Untitled%2046.png)
        
    - 위 쿼리문의 경우, parameter로 넘어온 value
    (alias 방식으로 SearchCriteria Class에 있는 value 값을 DTO 형식으로 넘긴 값)
    를 분석, value 값이 식사라면 첫 번째 <when> 태그 안의 내용을, value값이 음료라면 두 번째 <when> 태그 안의 내용을, value값이 식사와 음료 둘 다 아니라면 <otherwise> 태그 안의 내용을 쿼리문에 추가한다.
    - 따라서 parameter 값이 음료일때, 위 쿼리문의 WHERE절은 아래와 같다.
    WHERE A.ORDERABLE_STATUS = 'Y’ 
        AND A.CATEGORY_CODE IN (8, 9, 10)
- foreach
    - 동적 쿼리를 구현할 때, collection에 대한 반복 처리를 제공한다.
    - <foreach collection=”” item=””></foreach> 형식으로 사용하며, 추가적인 속성을 활용하여 반복 처리의 방법을 정의할 수 있다.
        - foreach 태그의 속성 참조
            
            ![Untitled](MyBatis/Untitled%2047.png)
            
        
![Untitled](MyBatis/Untitled%2048.png)
        
    - 위 예제에서의 <foreach> 태그의 정의는 아래와 같다.
    - collection=””을 통해 반복처리를 할 Collection을 매핑한다.
    - item=””을 통해 반복처리할 때 사용할 객체를 매핑한다.
    - open=””을 통해 반복문을 시작할 문자열을 지정한다.
    - separator=””를 통해 반복되는 객체를 나열할 때 사용할 구분자를 지정한다.
    - close=””을 통해 반복문을 종료할 때 사용할 문자열을 지정한다.
    - 위 for-each문의 실제 반복문은 아래와 같다.
        
![Untitled](MyBatis/Untitled%2049.png)
        
    - item=”4, 8, 11, 18, 19” (MENU_CODE)
- trim(where, set)
    - trim
        - <trim>은 쿼리의 구문의 특정 부분을 제거할 때 사용된다.
        - WHERE  엘리먼트가 기본적으로 처리하는 기능에 추가 규칙을 정의하기 위해 주로 사용된다.
        - <trim> 태그의 속성들을 이용하여 조건을 설정할 수 있으며, 그 속성들은 아래와 같다.
            
            ![Untitled](MyBatis/Untitled%2050.png)
            
            ![Untitled](MyBatis/Untitled%2051.png)
            
        - 위 코드의 해석은 아래와 같다.
        - <if> 태그를 통해 정의된 (categoryValue ≠ null) 조건과 (nameValue ≠ null) 조건이 모두 true일 때, 쿼리문은 prefix를 통해 엘리먼트의 가장 앞에 VALUE 값인 WHERE절을 추가한다. 또한, 쿼리의 AND 절은 엘리먼트(<trim></trim> 태그 내부 영역)의 가장 앞에 위치한 문장이 아니기에, 유지된다. 따라서 조건절은 
        WHERE A.CATEGORY_CODE = #{ categoryValue } 
            AND A.MENU_NAME LIKE LIKE '%' || #{ nameValue } || '%’
        이 된다.
        - 단, (categoryValue ≠ null) 조건이 false, (nameValue ≠ null) 조건이 true일 때는, 엘리먼트의 첫 문자열이 AND이므로, prefixOverride의 VALUE값인 AND에 해당, 해당 내용을 삭제한다.
        - 이후, prefix의 VALUE값인 WHERE절을 추가한다. 따라서 조건절은
        WHERE A.MENU_NAME LIKE '%' || #{ nameValue } || '%’가 된다.
            
            <aside>
            💡 prefix/prefixOverrides와 suffix/suffixOverrides의 처리 로직은 동일하다. VALUE값을 추가/삭제할 위치의 차이이며, 접미사와 접두사의 차이이다.
            
            </aside>
            
    - where
        - <where>는 기존 쿼리의 WHERE 절을 동적으로 구현할 때 사용한다.
        - <where></where> 태그 안의 쿼리문이 where 절로 시작하지 않을 때 자동으로 WHERE 절을 추가해준다.
        - 또한, 태그 내부에 모든 쿼리문이 추가되지 않는 상황인 경우, WHERE절은 무시된다.
        - 단, 조건에 따라 WHERE 태그가 두 개 이상 허용될 경우, 각각의 WHERE절이 모두 생성된다.
        - AND나 OR로 시작할 경우(WHERE절 없이)  해당 단어를 지워준다. (이후 WHERE절이 추가된다.)
            
            ![Untitled](MyBatis/Untitled%2052.png)
            
        - 위 코드의 경우, <if> 태그를 통해 걸린 조건(categoryValue ≠ null), (nameValue ≠ null)이 모두  true일 때, 코드는
            
            SELECT
            A.MENU_CODE
            , A.MENU_NAME
            , A.MENU_PRICE
            , A.CATEGORY_CODE
            , A.ORDERABLE_STATUS
            FROM TBL_MENU A
            WHERE A.CATEGORY_CODE = #{ categoryValue } 
                 AND A.MENU_NAME LIKE ‘%’ || #{ nameValue } || ‘%’
            
        - 가 되며, false일 때는 
          FROM TBL_MENU A
        WHERE A.MENU_NAME LIKE ‘%’ || #{ nameValue } || ‘%’
        가 된다.
        - (두 조건 모두 false일 때는 FROM TBL_MENU A 까지만 실행)
    - set
        - <set>은 기존의 UPDATE SET 절을 동적으로 구현할 때 사용한다.
        - <WHERE>와 처리 로직은 동일하다. SELECT문이 아닌, UPDATE문에 사용되며 <set> 구문을 자동으로 추가해주는 차이가 있다.
        - 또한, 문장의 첫 문자열이 “,”일 경우, 해당 문자열을 제거한다. (앞이든 뒤든)
            
            ![Untitled](MyBatis/Untitled%2053.png)
            
        - 위 코드의 경우, <if> 태그에 의해 정의된 조건절에 따라 처음으로 시행되는 문자열(엘리먼트)의 가장 앞에 SET 구문을 자동으로 추가해주며, 해당 처음으로 시작되는 문자열에 ,가 있을 경우 제거해준다. 따라서 (name ≠ null and name ≠ ‘’) 조건절은 false, 다른 두 조건은 모두 true일 때. 쿼리문은 아래와 같다.
        - UPDATE
                      TBL_MENU A
               SET A.CATEGORY_CODE = # { categoryCode }
                   ,  A.ORDERABLE_STATUS = #{ orderalbeStatus }
- bind (OGNL 방식)
    - OGNL(객체 그래프 탐색 기법): 자바의 패러다임으로, 객체를 가지고 해당하는 다른 객체를 찾아가는 방식이다. 
    DB의 패러다임은 PK를 통해 FK와의 관계를 탐색, 찾아다니는 방식이다.
    - 특정 구문을 미리 생성하여 쿼리에 적용해야 할 경우 사용한다.
    - <bind> 태그의 속성인 value의 _parameter 를 통해 전달받은 값에 접근하여 해당 구문을 생성한다.
- alias
    - parameterType의 경우, 패키지 이름을 포함한 풀 패키지명을 쓰는 것이 원칙이다.
    - 단 DB의 ALIAS 기능처럼 객체의 풀 패키지명을 별도의 별칭으로 지정한 뒤, 해당 별칭을 parameterType의 Value 값으로 지정하는 방식으로 번거로움을 줄일 수 있다.
    - ALIAS는 환경변수 설정을 하는 xml 파일에서 지정하며, 그 방식은 아래와 같다.
        
        ![Untitled](MyBatis/Untitled%2054.png)
        
    - 태그의 위치에 주의하자. <configuration> 태그의 아래, <environments> 태그의 위에 작성해야 한다.

# 4. MyBatis Mapper Element

- Cache
    - Cache는 컴퓨터 과학에서 데이터나 값을 미리 복사해 놓은 임시 장소를 가리킨다.
    - 캐시 접근 시간에 비해 원래 데이터를 접근하는 시간이 오래 걸리는 경우나, 값을 다시 게산하는 시간을 절약하고 싶은 경우 사용한다.
    - 캐시에 데이터를 미리 복사해 놓으면 계산이나 접근 시간 없이 더 빠른 속도로 데이터에 접근할 수 있다.
    - 동일한 DB 데이터에 다수 접근해야할 필요가 있을 때 유효하다. (캐시 생성에도 리소스가 소모되기 때문)
        
        ![Untitled](MyBatis/Untitled%2055.png)
        
- ResultMap
    - 데이터베이스를 다녀온 결과 데이터를 객체에 로드하는 방법을 정의하는 엘리먼트이다. resultMap에서 데이터를 가져올 때 작성되는 JDBC 코드를 줄여주는 역할의 대부분을 담당한다.
        
        ![Untitled](MyBatis/Untitled%2056.png)
        
- <constructor>
    - <constructor> 하위 엘리먼트 : 인스턴스화되는 클래스의 생성자에 결과를 삽입하기 위해 사용된다.
                
    ![Untitled](MyBatis/Untitled%2057.png)
                
    - <idArg> : ID인자. PK를 지정하는데 사용되며, ID와 같은 결과는 전반적으로 성능을 향상시킨다. (인덱스 스캐닝 개념)
    - <arg> 생성자에 삽입되는 일반적인 결과. 컬럼을 지정한다.
  - <association>
      - 복잡한 타입의 연관관계, 중첩된 결과 매핑에 사용된다. JOIN이 필요한 다중 테이블 DTO 객체를 ResultMap을 통해 매핑하기 손쉽게 해주는 역할.
      - 테이블과 테이블의 관계(카디널리티)가 1:1 관계일 때 사용된다.
                
          ![Untitled](MyBatis/Untitled%2058.png)
                
            
          <aside>
          💡 Java의 패러다임은 단방향, DB의 패러다임은 양방향이다. 그렇기에 TABLE JOIN시, JAVA의 DTO에 객체 정보를 담을 때 2개의 테이블 사이의 JOIN도 각각의 테이블에 대한 DTO 객체 한 개씩, JOIN 방향에 따른 DTO 객체 2개가 필요하다. 즉, 총 4개의 DTO가 필요하게 된다. 
          또한, 다중 JOIN이 필요할 때 테이블의 순서는 기준이 필요하다. 이 때의 기준은 최적화 개념(결과집합이 적을 수록 유리)을 생각하자.
            
          </aside>
            
      - <collection>
          - 본질적으로는 association과 유사하다.  테이블과 테이블의 카디널리티가 1:N관계일 때 사용되는 점이 차이점.
                
              ![Untitled](MyBatis/Untitled%2059.png)
                
      - <discriminator>
          - myBatis는 기본적으로 매핑 구문 아이디 별 결과 매핑은 고정되어 있는 상태이다.
          - 단, 매핑 구문은 동일하지만 동적으로 결과를 매핑해야 하는 상황이 필요해질 수도 있는데, 이 때 사용하는 것이 <discriminator>로, 매핑 과정에서 동적으로 결과를 매핑해 주는 기능을 제공한다.
- sql
    - 이 엘리먼트는 다른 구문에서 재사용가능한 SQL구문을 정의할 때 사용된다. 로딩시점에 정적으로 파라미터처럼 사용할 수 있다. 다른 프로퍼티값은 포함된 인스턴스에서 달라질 수 있다
    - xml 매퍼 파일 내부에서 전역 변수처럼 사용된다.
    - Key와 Value의 형식으로, 지정된 id값을 <include refid=””/>형식으로 호출한다.
        
        ![Untitled](MyBatis/Untitled%2060.png)
        
- insert
    - Insert, Update, Delete 엘리먼트는 사용하는 속성이 대부분 동일하다. 단, insert 엘리먼트는 추가적인 속성을 제공한다.
        
        ![Untitled](MyBatis/Untitled%2061.png)
        
    
    <aside>
    💡 <selectkey> : 마치 DB의 트리거처럼, INSERT, UPDATE, DELETE 엘리먼트가 이루어질 때 BEFORE, AFTER 속성을 이용하여 하나의 sqlSession에 값이 담겨 DB와 통신할 수 있다.
    
    </aside>
    
![Untitled](MyBatis/Untitled%2062.png)
    

# 5. MyBatis Config

- <properties>
    - 설정 파일에서 공통적인 속성을 정의하거나, 외부 파일에서 값을 가져와서 사용해야 하는 경우 별도의 properties 파일(외부 리소스)에 작성한 뒤, 설정 파일에서 해당 설정 값을 사용할 수 있다. 외부 파일은 resource 속성의 클래스 패스 하위 경로를 기술한다.
    - 패스 경로가 resource가 아닌 경우 url 속성의 “file:d:\”로 시작하는 경로를 기술하면 된다. 프로파티에 설정된 값을 꺼낼때는 ${ key } 표현식(리터럴 변수)를 사용한다.
    
    ![Untitled](MyBatis/Untitled%2063.png)
    
    ![Untitled](MyBatis/Untitled%2064.png)
    
- <settings>
    - settings 엘리먼트를 사용해서 설정하는 값들은 SqlSessionFactory 객체가 SqlSession 객체를
    만들 때 생성 할 객체의 특성을 설정한다.
    - settings 엘리먼트의 하위 엘리먼트들은 대부분 디폴트 값을 가지며, 특별한 경우가 아니면 디폴트 값을 사용해도 문제없이 잘 돌아간다.(캐시, statement 사영여부, 데이터를 동적으로 관련된 것만 동적하게 함, ...)
    
    ![Untitled](MyBatis/Untitled%2065.png)
    
    ![Untitled](MyBatis/Untitled%2066.png)
    
    ![Untitled](MyBatis/Untitled%2067.png)
    
- <typeAliases>
    - Mapper에서 대부분의 DTO 혹은 VO로 타입을 지정하는 경우 full-name으로 클래스명을 작성해야 하기 때문에 상당히 길다. 따라서 오타 발생 확률도 높아지고 귀찮은 부분이 있다. 이러한 불편을 해결하기 위해 타입별 별칭을 설정할 수 있다.
        
    ![Untitled](MyBatis/Untitled%2068.png)
        
        <aside>
        💡 원시 타입(기본 자료형)이나 흔하게 사용되는 자바 타입(ex:string)에 대해서는 마이바티스 내부에 미리 정의된 별칭이 있다. 자세한 사항은 위 Mapper 인스턴스(xml 방식)의 내장별칭 참조
        
        </aside>
        
- <typeHandler>
    - PreparedStatement에서 파라미터를 설정하거나 결과 셋을 가져올 때 테이블 각 컬럼을 자바의 적절한 타입으로 설정해서 가져오기 위해 타입 핸들러를 사용하게 된다.
    - 하지만 대부분 마이바티스가 정의한 타입 핸들러가 있기 때문에 대부분의 경우에는 개발자가 별도로 타입핸들러를 만들 필요가 없다. 데이터베이스에 저장된 자바 코드를 자바의 ENUM으로 변환할 때 사용하면 유용하다.
        
    ![Untitled](MyBatis/Untitled%2069.png)
        
    ![Untitled](MyBatis/Untitled%2070.png)
        
- <objectFactory>
    - 대부분의 경우 결과 셋으로 사용하는 모델을 만들 때 컬럼에 매핑되는 setter 메소드를 호출해서 객체를 만든다. 하지만 setter 메소드가 없으면 자바의 리플렉션을 사용해서 값을 설정한다.
    - 테이블간의 관계가 복잡하거나 단순히 setter 메소드를 호출하는 것 이상의 과정을 거쳐야 하는 아주 극소수의 경우 objectFactory를 사용할 수 있다.
    - 두 개의 create메소드를 가지고 있으며 하나는 디폴트 생성자를 처리하고 다른 하나는 파라미터를 가진 생성자를 처리한다. 마지막으로 setProperties 메소드는 ObjectFactory를 설정하기 위해 사용될 수 있다. objectFactory엘리먼트에 정의된 프로퍼티는 ObjectFactory인스턴스가 초기화된 후 setProperties에 전달될 것이다.
        
    ![Untitled](MyBatis/Untitled%2071.png)
        
- <plugins>
    - 마이바티스가 해당 구문을 실행하는 과정에서 특정 시점의 처리를 가로채 부가적인 작업을 처리할 수 있다. 로그를 출력할 수도 있으며, 파라미터에 대해 공통적으로 타입 체크나, 결과셋에 대한 처리를 추가할 수도 있다. <spring의 AOP나 인터셉터와 유사하다.
    - 이 클래스들의 메소드는 각각 메소드 시그니처를 통해 찾을 수 있고 소스코드는 마이바티스 릴리즈 파일에서 찾을 수 있다. 오버라이드할 메소드의 행위를 이해해야만 한다. 주어진 메소드의 행위를 변경하거나 오버라이드하고자 한다면 마이바티스의 핵심기능에 악영향을 줄 수도 있다.
    - 이러한 로우레벨 클래스와 메소드들은 주의를 해서 사용해야 한다. 플러그인을 사용하도록 처리하는 방법은 간단하다. Interceptor인터페이스를 구현해서 가로채고(intercept) 싶은 시그니처를 명시해야 한다.
        
    ![Untitled](MyBatis/Untitled%2072.png)
        
    ![Untitled](MyBatis/Untitled%2073.png)
        
    
- <environments>
    - 마이바티스의 트랜젝션 관리자와 데이터 소스 두 가지를 설정할 수 있다. 트랜젝션 관리자와 데이터 소스는 마이바티스만 단독으로 사용할 때는 필요하지만, 스프링 연동 모델을 사용할 경우 필요 없다. (스프링의 설정을 따름)
    - 트랜젝션 관리자
    - JDBC: 마이바티스 API에서 제공하는 commit, rollback 메소드 등을 사용해서 트랜잭션을 관리하는 방식
        
    ![Untitled](MyBatis/Untitled%2074.png)
        
    - MANAGED: 마이바티스 API보다는 컨테이너가 직접 트랜잭션을 관리하는 방식
        
        ![Untitled](MyBatis/Untitled%2075.png)
        
    - 데이터 소스
    - UNPOOLED: 데이터베이스에 요청할 때마다 데이터베이스 연결을 새롭게 생성하고 처리 후 완전히 해제한다. (성능이 저하됨)
        
        ![Untitled](MyBatis/Untitled%2076.png)
        
    - POOLED: 일정 수의 데이터베이스 연결을 커넥션 풀이라는 메모리 영역에 두고 필요할 때마다 가져다 사용하고 사용이 완료되면 다시 풀에 반납한다. (HEAP 영역)
        
        ![Untitled](MyBatis/Untitled%2077.png)
        
        <aside>
        💡 POOLED와 UNPOOLED의 차이는 인스턴스의 생성 유무로 판단하자.
        
        </aside>
        
    - JNDI: 디렉터리 서비스를 위해 자바가 제공하는 인터페이스로 WAS서버(ex: 톰캣)에서 관리한다.
        
        ![Untitled](MyBatis/Untitled%2078.png)
        
- <mappers>
    - 매퍼를 지정하는 엘리먼트. 설정하는 방법은 4가지가 있다.
    1. 클레스패스에 위치한 xml매퍼 파일 지정(mapper 엘리먼트의 resource 속성)
    2. URL을 사용한 xml 매퍼 파일 지정(mapper 엘리먼트의 url 속성)
    3. 매퍼 인터페이스를 사용하는 인터페이스 위치 지정(mapper 엘리먼트의 class 속성)
    4. 패키지 지정으로 패키지 내 자동으로 매퍼 검색(package 엘리먼트의 name 속성)
        
    ![Untitled](MyBatis/Untitled%2079.png)