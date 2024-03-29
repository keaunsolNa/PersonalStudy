# PL/SQL 교육자료

### 2023.05.30

### 나큰솔

- 개념 및 정의, 목적
    - PL/SQL (PROCEDURE LANGUAGE EXTNESION TO SQL || PROCEDURE LANGUAGE/SQL)
    - PL/SQL은 DBMS에서 사용하는 절차적 언어
    - SQL의 단점을 보완하여 SQL 문장 내에서 변수의 정의, 조건 처리, 반복문 처리, 예외 처리 등을 지원하기 위해 만들어졌다.
    - OSI 계층에 따라 데이터를 오고 가는 과정에서 Performence는 필연적으로 떨어진다. PL/SQL은 Data만을 다룰 때, 계층 간 이동에 따르는 Performence의 저하를 최소화 하기 위해 만들어졌다.
    - PL/SQL은 크게 PL/SQL Blocks, Sections, Comments의 3가지 부분이 있다.
        - PL/SQL Blocks
            - 관련된 선언 및 명령문을 모으는 별개의 Block.
            - PL/SQL Blocks는 선언(Declare절), 주요 프로그램 Body (Procedure Body Section), 예외 핸들러(Exception Handler)의 3가지 Section으로 이루어져 있다.
        - Sections
            - 선언부(Declare Section) : Block의 다른 모든 부분에서 엑세스할 수 있는 모든 변수, 상수, Exception, Cursor 등을 선언하는 곳
            - Procedure Body(Execute Section) : Block에 대해 실행 가능한 명령이 포함되는 곳으로, PL/SQL Block의 처리 기능을 기술하는 곳
            - Exception Handler Section : Block에 대한 예외 핸들러로, Block의 Body에 있는 명령에서 예외가 발생하면 해당 Exception Section이 있는 핸들러로 제어가 넘어간다.
        - Comment
            
            ```sql
            -- PROCEDE A SINGLE-LINE COMMENT WITH A DOUBLE-HYPHEN.
            /* DELIMIT A MULTI-LINE COMMENT WITH "/*" AS A PREFIX AND "*/"
                AS A SUFFIX. A MILTI-LINE COMMENT CAN CONTAIN ANY NUMBER OF 
                LINES. */
            ```
            
- Blcok
    
    ![Untitled](PL_SQL_교육자료/Untitled.png)
    
    - PL/SQL Block의 유형
        - Anonymous Block :
            - 익명 블록, 프로시저나 함수, 트리거의 본문을 형성하지 않는 블록이다.
                
                ```sql
                DECLARE
                	av_str VARCHAR2(20);
                BEGIN
                	av_str := '본문';
                	DBMS_OUTPUT.PUT_LINE('TEST : ' || av_str );
                EXCEPTION
                	WHEN NO_DATA_FOUND THEN
                	DBMS_OUTPUT.PUT_LINE('NO DATA FOUND EXCEPTION 발생!');
                END;
                /
                
                ==>
                TEST : 본문
                PL/SQL 프로시저가 성공적으로 완료되었습니다.
                ```
                
        - Function Block :
            - 함수 블록. DECLARE 예약어 대신 그 자리에 함수 헤더가 온다.
            - 함수 헤더는 함수 이름과 파라미터를 기술하고, 반환값의 자료형을 나타낸다.
        - Procedure Blcok :
            - Function 블록과 비슷하지만, Function Block과는 달리 값을 반환하지 않으며 식에 활용할 수 있다.
        - Package Block
            - Block 내에 Block이 들어가는 형태.
            - 변수는 블록 내부에서만 사용 가능하며, 상위 레벨의 변수는 하위 레벨에서 모두 사용 가능
        - Trigger Block
            - 지정된 이벤트가 발생하면 자동으로 실행되는 블록.
- 변수
    
    ![Untitled](PL_SQL_교육자료/Untitled%201.png)
    
    - 변수 명명 규칙
        
        ![Untitled](PL_SQL_교육자료/Untitled%202.png)
        
        - 변수명 : 영어, 숫자, 특수문자 등을 사용한다. 변수명은 반드시 문자로 시작하며, 최대 30자. 대소문자는 구분하지 않는다.
        - constant : 상수로 선언할 경우 사용한다. 단, 상수로 선언 시 반드시 초기 값 선언이 필요하다.
        - 변수 형태 : 변수의 데이터 형태 및 그 크기를 선언한다.
        - [not null] 해당 변수에 not null 제약 조건을 부여한다. 이 경우 반드시 초기 값 선언이 필요하다.
        - 초기 값 : 초기 값 설정이 필요한 경우 사용한다. 수식, 상수 등 어느 것도 무방하다.
    - %TYPE, %ROWTYPE
        - % 연산자를 사용하여 %TYPE, %ROWTYPE 변수를 지정할 수 있다.
        - 참조할 테이블의 데이터 타입을 그대로 가져온다.
        - %TYPE의 경우 하나의 값, %ROWTYPE은 하나 이상의 값(Collection, Object 변수)에 적용한다.
            
            ```sql
            :table.column%type, variable%type
            :table%rowtype
            ```
            
    - 테이블 타입의 변수
        - 1차원 배열과 유사한 형태로, Binary_Integer와 Scalar 타입으로 구성되어 있다.
        - 제한없이 동적으로 배열의 크기가 커진다.
        - 데이터베이스의 테이블과는 다르다.
            
            ```sql
            DECLARE
            
              TYPE an_emp_id_table_type IS TABLE OF PHM_EMP.EMP_ID%TYPE
              INDEX BY BINARY_INTEGER;
              
              TYPE an_emp_no_table_type IS TABLE OF PHM_EMP.EMP_NO%TYPE
              INDEX BY BINARY_INTEGER;
              
              an_emp_id_table an_emp_id_table_type;
              an_emp_no_table an_emp_no_table_type ;  
            
              I BINARY_INTEGER := 1;
            
            BEGIN
              FOR IDX IN (SELECT EMP_ID, EMP_NO FROM PHM_EMP)
                LOOP
                  an_emp_id_table(I) := IDX.EMP_ID;
                  an_emp_no_table(I) := IDX.EMP_NO;
                  
                  I := I + 1;
              END LOOP;
              
              FOR J  IN 1..(I-1)
                LOOP
                  DBMS_OUTPUT.PUT_LINE('EMP_ID: ' || an_emp_id_table(J) || ', EMP_NO: ' || an_emp_no_table(J));
              END LOOP;  
            END;
            /
            ```
            
    - 레코드 타입의 변수
        - 여러 개의 타입을 가진 논리적 단위의 데이터 그룹
        - 하나 이상의 Scalar 또는 Record, Table 형의 필드를 포함해야 한다.
        - 초기값을 정할 수 있으며, 초기값이 없을 경우 Null이 초기값이 된다.
        - %ROWTYPE과의 차이는 () 안의 컬럼만을 대상으로 한다는 점
            
            ```sql
            DECLARE 
              TYPE ar_emp_record_type IS RECORD
              (
                an_emp_id PHM_EMP.EMP_ID%TYPE,
                av_last_nm PHM_NAME.LAST_NM%TYPE
              );
              
              ar_emp_record ar_emp_record_type;
            BEGIN
              SELECT A.EMP_ID
                   , B.LAST_NM
                INTO ar_emp_record
                FROM PHM_EMP A
                LEFT JOIN PHM_NAME B ON (A.EMP_ID = B.EMP_ID)
               WHERE A.EMP_ID = '&사번' 
                 AND B.NAME_TYPE_CD = 'KOR';
              
              DBMS_OUTPUT.PUT_LINE('사번: ' || ar_emp_record.an_emp_id);
              DBMS_OUTPUT.PUT_LINE('이름: ' || ar_emp_record.av_last_nm );
            END;
            /
            ```
            
- 제어문
    - IF 문
        
        ```sql
        DECLARE
          an_SCORE NUMBER;
          av_GRADE VARCHAR2(3);
        BEGIN
          an_SCORE := '&점수';
           
          IF an_SCORE >= 90 THEN av_GRADE := 'A';
          ELSIF an_SCORE >= 80 THEN av_GRADE := 'B';
          ELSIF an_SCORE >= 70 THEN av_GRADE := 'C';
          ELSIF an_SCORE >= 60 THEN av_GRADE := 'D';
          ELSE av_GRADE := 'F';
          END IF;
          
          DBMS_OUTPUT.PUT_LINE('당신의 점수는 ' || an_SCORE
                                || '점이고, 학점은 ' || av_GRADE
                                || '학점입니다.'); 
        END;
        /
        ```
        
    - CASE 문
        
        ```sql
        DECLARE
          an_vempno VI_FRM_PHM_EMP.EMP_ID%TYPE;
          av_vname VI_FRM_PHM_EMP.EMP_NM%TYPE;
          av_vinoff VI_FRM_PHM_EMP.IN_OFFI_YN%TYPE;
          av_vanswer VARCHAR2(20) := NULL;
        BEGIN
          SELECT A.EMP_ID
               , A.EMP_NM
               , A.IN_OFFI_YN
            INTO an_vempno
               , av_vname
               , av_vinoff
            FROM VI_FRM_PHM_EMP A
           WHERE A.EMP_ID = '&사번'
             AND LOCALE_CD = 'KO';
           
          av_vanswer := CASE av_vinoff
                      WHEN 'Y' THEN '재직자'   
                      WHEN 'N' THEN '퇴직자'
                      ELSE '에러'
                    END;
          
          DBMS_OUTPUT.PUT_LINE ('사번         이름          퇴직여부');
          DBMS_OUTPUT.PUT_LINE ('-------------------------------');
          DBMS_OUTPUT.PUT_LINE (an_vempno   || '       ' || av_vname || '        ' || av_vinoff);
          
          
          EXCEPTION WHEN NO_DATA_FOUND THEN 
          DBMS_OUTPUT.PUT_LINE('존재하지 않는 사번입니다.');
        END;
        /
        
        SELECT * FROM VI_FRM_PHM_EMP;
        ```
        
- 반복문
    - LOOP 문
        
        ```sql
        DECLARE
          an_number NUMBER := 0;
          an_answer NUMBER := 1;
          an_factorial NUMBER := TO_NUMBER('&숫자');
        BEGIN
          LOOP
            an_number := an_number + 1;
            an_answer := an_number * (an_answer);
            IF an_number >= an_factorial THEN EXIT;
            END IF;
          END LOOP;
          
          DBMS_OUTPUT.PUT_LINE(an_answer);
        END;
        /
        ```
        
    - FOR IN 문
        
        ```sql
        DECLARE
          an_result NUMBER;
        BEGIN
          FOR DAN IN 2..9
            LOOP
              IF MOD(DAN , 2) = 0
                THEN FOR SU IN 1..9
                       LOOP
                         an_result := DAN * SU;
                         DBMS_OUTPUT.PUT_LINE(DAN || ' * ' || SU || ' = ' || an_result);
                     END LOOP; 
                     DBMS_OUTPUT.PUT_LINE('');
              END IF;
          END LOOP;
        END;
        /
        ```
        
- Transaction, ACID
    - 트랜잭션 : DBMS에서 발생하는 1개 이상의 명령어들을 하나의 논리 집합으로 묶어 놓은 단위.
    - DML
        - SELECT : 데이터베이스에 들어 있는 데이터를 조회하거나 검색하기 위한 명령어
        - INSERT : 데이터베이스에 데이터를 삽입하기 위한 명령어
        - UPDATE : 데이터베이스의 데이터를 수정하기 위한 명령어
        - DELETE : 데이터베이스의 데이터를 삭제하기 위한 명령어
    - ACID
        - 원자성(Atomicity) : Transaction의 어떤 상태로든 변경은 원소적이다. 모두 발생하거나 모두 발생하지 않는다.
        - 일관성(Consistency) : Transaction은 올바른 상태의 변환이다. 수행된 행위는 그 상태와 관련된 어떠한 무결성 제약조건도 어기지 않는다.
        - 고립성(Isolation) : 많은 Transaction이 동시에 실행될 수 있으나, 어떤 Transaction의 관점에서든 다른 Transaction이 자신이 실행된 앞이나 뒤에 실행된 것으로 나타난다.
        - 영구성(Durability) : Transaction이 성공적으로 완료되면 상태의 변경은 영구적이며, 다음에 장애가 발생하여도 그대로 존속한다.
    - 참조
        
        [DBMS는 어떻게 트랜잭션을 관리할까?](https://d2.naver.com/helloworld/407507)
        
- Cursor
    - 정의
        - Cursor는 대상이 되는 데이터베이스 데이터를 일시적으로 저장하는 기능을 가진 메모리상의 Buffer. 모든 SQL 문은 그 수행 과정에서 Cursor를 사용한다.
        - Cursor를 통해 메모리에 존재하는 SQL문 실행결과를 바로 접근하여 fetch할 수 있다.
        - 커서는 현재 처리하고 있는 row를 가리키며, 1개의 row씩 처리하다가 마지막까지 처리가 끝났을 때 cursor를 닫는다.
        - 여러 record를 읽어 순차적으로 처리하는 경우에는 프로그램에서는 그에 해당하는 Cursor를 그 이름을 부여하여 사용해야 한다.
        - SQL문이 수행될 때 자동으로 사용되는 프로그램에서, 정의하지 않은 Cursor를 암시적 커서(implicit cursor)라 칭하며 그 명칭은 “SQL”이다.
        - 한 편 프로그램에서 정의한 Cursor는 명시적 커서(explicit cursor)라 칭하며, 그 명칭은 이용자(프로그램)가 부여한다.
            - 명시적 커서 :
                - Declare (sql 생성 부분)
                - Open (커서를 연다)
                - Fetch (현재 row를 지정된 변수에 로드한다.)
                - Close (커서를 닫는다.)
                - Deallocate (할당 해제, 커서 정의를 삭제하고 관련된 모든 시스템 리소스를 해제한다. )
        - PL/SQL에서는 SQL의 결과 Set에 두 개 이상의 행이 있을 경우에는 에러가 발생한다. 따라서 PL/SQL에서 여러 행을 읽는 작업이 필요할 경우에는 Cursor를 사용해야 한다.
    - 주요 용어
        
        
        - 커서 범위:
            - GLOBAL(해당 커서가 연결에 대해 전역임을 지정)
            - LOCAL(해당 커서가 저장 프로시저, 트리거 또는 커서를 보유하는 쿼리에 대해 로컬임을 지정)
            
        - 데이터 가져오기 옵션 :
            - FORWARD_ONLY : 커서가 첫 번째 행에서 마지막 행으로만 스크롤될 수 있도록 지정
            - SCROLL :  데이터를 가져오는 6가지 옵션(FIRST, LAST, PRIOR, NEXT, RELATIVE, ABSOLUTE)를 제공한다.
        
        - 커서 유형:
            - STATIC CURSOR : 정적 커서는 커서를 만드는 동안 결과 집합을 채우고, 쿼리 결과는 커서의 수명 동안 케시된다. 앞뒤로 이동할 수 있다.
            - FAST_FORWARD : 커서의 기본 유형. 앞으로만 스크롤할 수 있다는 점을 제외하면 STATIC CURSOR와 동일
            - DYNAMIC : 동적 커서에서는 커서가 열려 있는 동안 데이터 소스의 다른 사용자에 대해 추가 및 삭제를 볼 수 있다.
            - KEYSET : 다른 사람들이 추가한 레코드를 볼 수 없다는 점을 제외하면 DYNAMIC과 동일. 타 사용자가 레코드를 삭제할 시 레코드 집합에 엑세스할 수 없다.
        
        - 자물쇠의 종류
            - 잠금은 DBMS가 다중 사용자 환경에서 행에 대한 엑세스를 제한하는 프로세스. 행 또는 열이 독점적으로 잠긴 경우 잠금이 해제될 때까지 타 사용자는 잠긴 데이터에 엑세스 불가능. 데이터 무결성을 위해 사용되며, 두 사용자가 한 행의 동일한 열을 동시에 업데이트 불가능하다.
            - READ ONLY : 커서를 업데이트 할 수 없도록 지정
            - SCROLL_LOCKS : 커서에 데이터 무결성 제공. 커서를 사용하여 수행한 업데이트 또는 삭제가 성공할 수 있도록 커서가 행을 커서로 읽을 때 커서가 행을 잠그도록 지정
            - OPTIMITIC : 커서를 읽을 때 커서가 행을 잠그지 않도록 지정. 커서를 사용하여 수행한 업데이트 또는 삭제는 행이 커서 외부에서 업데이트 될 경우 성공하지 못 한다.
    - 커서 속성
        - %ROWCOUNT : 가장 최근에 인출한 행의 개수
        - %FOUND : 가장 최근에 인출한 행이 있으면 TRUE
        - %NOTFOUND : 가장 최근에 인출한 행이 없으면 TRUE
        - %ISOPEN : Cursor가 열려 있으면 TRUE
    - Cursor Parameter
        - Syntex : OPEN CURSOR(parameter1, parameter2, …)
        - 커서에 파라미터를 정의하고 OPEN 시 파라미터를 전달할 수 있다.
        - 실행할 때마다 이전에 사용했던 파라미터의 활성 집합을 닫고, 매번 새 파라미터를 이용해 커서를 OPEN한다.
            
            ```sql
            DECLARE
            
              av_emp_nm_temp VARCHAR2(20);
              
              CURSOR CUSTOMERS_CURSOR (an_emp_id_input NUMBER) IS
              SELECT 
                     EMP_NM
                FROM VI_FRM_PHM_EMP
               WHERE EMP_ID = an_emp_id_input
                 AND LOCALE_CD = 'KO';
              
              BEGIN
                OPEN CUSTOMERS_CURSOR('&사번');
                LOOP
                  FETCH CUSTOMERS_CURSOR INTO av_emp_nm_temp;
                  EXIT WHEN CUSTOMERS_CURSOR%NOTFOUND;
                  
                   DBMS_OUTPUT.PUT_LINE('입력값으로 받은 사원 이름 : ' || av_emp_nm_temp);
                END LOOP;
               
              CLOSE CUSTOMERS_CURSOR;
            
              OPEN CUSTOMERS_CURSOR(0241);
                LOOP
                  FETCH CUSTOMERS_CURSOR INTO av_emp_nm_temp;
                  EXIT WHEN CUSTOMERS_CURSOR%NOTFOUND;
                  
                   DBMS_OUTPUT.PUT_LINE('사원번호 0241번의 사원 이름 : ' || av_emp_nm_temp);
                END LOOP;
               
              CLOSE CUSTOMERS_CURSOR;  
            END;
            /
            ```
            
    - Cursor for Loop
        - Cursor for loop는 cursor의 open, fetch, close와 Numeric for loop가 결합된 형태로다음과 같은 문법에 의한다.
            
            <aside>
            💡 FOR <record_name> IN <cursor_name[(parameter_value,...)] LOOP
            
            statement..................;
            
            END LOOP;
            
            </aside>
            
        - OPEN, FETCH, CLOSE는 기술되지 않는다
        - Cursor를 재 open하기 위해 loop내부에 open문을 기술할 수는 없다
        - For Loop가 시작되는 시점에 내부적으로 Cursor가 OPEN된다.
        - 매 Loop 시작 시점에 내부적으로 Fetch가 이루어져, Loop 내부에서는 각각의 record에 대한 처리문만 기술되고 이때 record의 각 항목에 대한 언급은 record_name.column_name으로 한다
        - 더이상 Fetch할 기록이 없으면 Loop가 종료되며 내부적으로 Close가 수행된 후, cursor for loop문의 수행이 종료하게 된다.
        - <record_name>은 declare문에 기술 할 필요는 없으며 for loop가 수행되는 동안 내부적으로 다음과 같은 선언 효과가 있다
            
            <aside>
            💡 DECLARE
            <record_name>    <cursor_name>%TYPE;
            
            </aside>
            
            ```sql
            DECLARE 
              CURSOR CUSTOM_CURSOR IS
                SELECT 
                       IN_OFFI_YN
                     , EMP_NM 
                  FROM VI_FRM_PHM_EMP 
                 WHERE LOCALE_CD = 'KO';
                at_record_emp CUSTOM_CURSOR%ROWTYPE;
              BEGIN
                FOR at_record_emp IN CUSTOM_CURSOR LOOP
                  IF at_record_emp.IN_OFFI_YN = 'Y' THEN
                    DBMS_OUTPUT.PUT_LINE('퇴직사원 이름 : ' || at_record_emp.EMP_NM);
                  ELSE DBMS_OUTPUT.PUT_LINE('재직사원 이름 : ' || at_record_emp.EMP_NM);
                  END IF;
                END LOOP;
            END;
            /
            ```
            
- EXCEPTION HANDLING
    - Exception이라 함은 PL/SQL프로그램의 begin이후 정상적인 처리 중 발생하는 비정상적인 경우를 말하고, 이에 대처하는 처리 내용을 기술하는 부분이 Exception절(Exception Handler)이라 할 수 있다.
    - 모든 ORACLE Error는 자동적으로 Exception을 발생 시키며 이들 중 자주 쓰이는 것들은 그 고유한 명칭을 갖고 있다. 또한 프로그램을 통하여 이용자가 Exception을 정의하여 사용할 수 있는데 전자를 Predefined Internal Exception이라 하고 후자를 User-defined Exception이라 한다.
    - Predefined Internal Exception
        
        ![Untitled](PL_SQL_교육자료/Untitled%203.png)
        
    - User-defined Exception
        
        ```sql
        DECLARE 
          on_emp_id          NUMBER;
          ov_in_offi_yn      VARCHAR2(30);
          oe_no_more_work    EXCEPTION;
          
          BEGIN
        
          on_emp_id := '&사번';
          
          SELECT IN_OFFI_YN
            INTO ov_in_offi_yn
            FROM VI_FRM_PHM_EMP 
           WHERE EMP_ID = on_emp_id
             AND LOCALE_CD = 'KO';
             
             IF ov_in_offi_yn = 'N'
             THEN RAISE oe_no_more_work; 
             END IF;
                 
          DBMS_OUTPUT.PUT_LINE('현재 재직 중인 사원입니다. ');     
          
          EXCEPTION
            WHEN NO_DATA_FOUND THEN
                 DBMS_OUTPUT.PUT_LINE('사원 정보가 존재하지 않습니다. ');
            WHEN oe_no_more_work THEN
                 DBMS_OUTPUT.PUT_LINE('더 이상 재직 중인 사원이 아닙니다. ');
                 
        END;
        /
        ```
        
- 예제
    - **P_ELA_REAPPLY**
    - **P_FRM_LOGIN_CHECK**
    - **P_MAKE_ENTITY**
    - **P_ELA_PAY_ACCOUNT_REQ**
    - P_ELA_SET_APPL_INFO
    - P_ELA_EXT_CONFIRM
    - P_FRM_AUTH_LOG
    - P_FRM_END_YMD_CHECK
    - P_INT_MAKE_ECLIST_Y11
    - P_PAY_MST_CHANGE_MAKE
    - P_PAY_MST_CHANGE_INSERT
    - P_PAY_MST_CHANGE_PAY
    - P_PAY_CLOSE_CHECK
- 과제
    - 문 1 : 수 3개, 연산자 2개를 Parameter로 하는 사칙연산 계산기를 PL/SQL로 작성하세요
    - 문 2 : V_PHM_EMP_COPY VIEW를 CREATE한 후, PHM_ACCOUNT_TEMP TABLE을 SELECT 해서 V_PHM_EMP_COPY_VIEW에 INSERT 하는 PROCEDURE를 작성하세요.
    - 문 3 : PHM_ACCOUNT_TEMP TABLE을 CREATE한 후, 로그인 시 접속 아이디(사원 번호), 접속 아이피, 접속 시간을  INSERT 하도록 P_FRM_LOGIN_CHECK PROCEDURE를 수정하세요.
    - 문 4 : PHM_ACCOUNT_TEMP 에 기록된 접속 시간을 바탕으로 마지막 접속 시간이 현재 시간으로부터 1년 이상 지났을 경우, PHM_ACCOUNT_TEMP의 DORMANT_YN COLUMN을 N에서 Y로 변경하도록 하는 PROCEDURE를 작성하세요.
    - PHM_ACCOUNT_TEMP :
        
        ```sql
        CREATE TABLE PHM_ACCOUNT_TEMP 
        (
          PHM_ACCOUNT_TEMP_NO NUMBER PRIMARY KEY,
          EMP_NO NUMBER,
          ACCOUNT_IP VARCHAR2(40),
          ACCOUNT_TIME DATE,
          DORMANT_YN CHAR(1) DEFAULT 'N'
        );
        
        COMMENT ON COLUMN PHM_ACCOUNT_TEMP.PHM_ACCOUNT_TEMP_NO IS '사원 접속 PK';  
        COMMENT ON COLUMN PHM_ACCOUNT_TEMP.EMP_NO IS '접속 아이디';
        COMMENT ON COLUMN PHM_ACCOUNT_TEMP.ACCOUNT_IP IS '접속 IP';
        COMMENT ON COLUMN PHM_ACCOUNT_TEMP.ACCOUNT_TIME IS '접속 시간';
        COMMENT ON COLUMN PHM_ACCOUNT_TEMP.DORMANT_YN IS '휴면 여부';
        ```