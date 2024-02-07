# DATABASE - Oracle

<aside>
💡 5: SELECT
1: FROM
2: WHERE
3: GROUP BY
4: HAVING
6: ORDER BY

</aside>

# 1. ORIENTATION

- Data & Database
    - Data란 관찰의 결과로 나타난 정량적 혹은 정성적인 실제 값을 의미한다.
    - 정보란 데이터를 기반으로 하여 의미를 부여한 것이다.
    - Database란 한 조직에 필요한 정보를 여러 응용 시스템에서 공용할 수 있도록 논리적으로 연관된 데이터를 모으고, 중복되는 데이터를 최소화하여 구조적으로 통합/저장해 놓은 곳이다.
    - 데이터베이스는 운용 데이터, 공용 데이터, 통합 데이터, 저장 데이터 이다.
    - Database의 특징은 아래의 4가지가 있다.
    1. 실시간 접근성(Real Time Accessibility) : 사용자가 데이터를 요청하면 실시간으로 결과를 서비스한다.
    2. 계속적인 변화(Continuous Change) : 데이터 값은 시간에 따라 항상 바뀐다.
    3. 동시 공유(Concurrent Sharing) : 데이터베이스는 서로 다른 업무 또는 여러 사용자에게 동시 공유된다.
    4. 내용에 따른 참조(Reference By Content) : 데이터베이스에 저장된 데이터는 데이터의 물리적 위치가 아니라 데이터 값에 따라 참조된다.
- DBMS
    - DBMS란 DataBase Management System의 약자로, 데이터베이스에서 데이터를 추출, 조작, 정의, 제어 등을 할 수 있게 해주는 데이터베이스 전용 관리 프로그램이다.
    - DBMS는 다음의 4가지 기능을 한다.
    1. 데이터 추출(Retrieval) : 사용자가 조회하는 데이터 혹은 응용 프로그램의 데이터를 추출한다.
    2. 데이터 조작(Manipulation) : 데이터를 조작하는 소프트웨어가 요청하는 데이터의 삽입
    3. 데이터 정의(Definition) : 데이터의 구조를 정의하고 데이터 구조에 대한 삭제 및 변경 수행
    4. 데이터 제어(Control) : 데이터베이스 사용자를 생성하고 모니터링하여 접근을 제어함.
    - DBMS의 사용 이점
        
        ![10.png](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/10.png)
        
        <aside>
        💡 무결성이란 원하는 결과를 도출하는, 무결점이다.
        
        </aside>
        
- Oracle Developer
    - 접속기 : SQL Developer가 제공하는 것. SQL에 있는 것은 아니다. 여러 가지 계정을 더블 클릭만으로 접속할 수 있도록 도와주는 역할을 한다. (아이디와 비밀번호를 입력하지 않아도)
    - 계정 : sys(최상위의 다른 계정을 관리하는 관리자 계정), system(관리자 계정), scott(test 계정)
    관리자 계정은 create, drop, grant, revoke등의 명령어로 다른 계정을 관리할 수 있다.
    C##계정 처럼 만들어진 (일반)계정은 자기만의 여러 가지 객체들을 (테이블 같은) 가지고 있다. 
    모든 계정은 권한 역시 포함한다.
    테이블은 선반과 같은 역할로, 정보를 담아두는 역할을 한다.
- 주요 용어
    
    ![1.png](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/1.png)
    
    - SQL(Structured Query Language) : 구조화된 질의 문. DBMS에서 데이터를 조회하거나 조작하기 위해 사용하는 표준 검색 언어.
    1. 행, 튜플
    2. 컬럼, 도메인
    3. 기본키(Primary Key) : 절대 중복되지 않고, 비어있으면 안 되는 컬럼. 이는 튜플을 구분하기 위한 목적으로 기본키를 사용하기 때문이다.
    4. 외래키(Foreign Key) : 서로 관계가 있는 컬럼을  JOIN으로 합쳤을 때, 합쳐진 테이블의 기본 키.
    5. Null : 비어 있는 한 칸.
    6. 컬럼값, 속성값 : 한 칸 한 칸

# 2. DATA TYPE

![2.png](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/2.png)

- NUMBER
    - NUMBER : 숫자. JAVA와는 달리 모든 숫자형 데이터 타입은 NUMBER.
- CHARACTER
    - CHAR : 고정길이 문자형. 지정한 크기보다 작은 문자열이 입력 가능하고, 남은 공간은 공백으로 채운다.  LENGTH의 경우, 글자의 숫자만큼의 정수에 남은 바이트를 합한 숫자를  반환한다. LENGTHB의 경우, 글자의 바이트 값만큼의 정수와 할당된 수치에서 남은 바이트를 합한 숫자를 반환한다.
    NULL값은 NULL값으로 출력된다.
    - VARCHAR2 : 가변길이 문자형. LENGTH의 경우, 글자의 숫자만큼의 정수값을 반환한다. (남은 바이트는 감소한다)  LENGTHB의 경우, 글자의 바이트 값만큼의 정수를 반환한다. (남은 바이트는 감소한다.) NULL값은 NULL값으로 출력된다.
    - NVARCHAR2 : 가변길이 문자형. LENGTH의 경우, 글자의 숫자만큼의 정수값을 반환한다. (남은 바이트는 감소한다.) LENGTHB의 경우, 글자당 2바이트로 인식하며 (한글, 영어, 숫자 등 모든 것들) 남은 바이트는 감소한다.
    
    ![3.png](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/3.png)
    
- DATE
    - 날짜형 데이터 타입. JAVA와는 달리 Calendar 타입 등은 없고,  DATE형 하나로 표시한다.
- LOB
    - CLOB : 가변길이 문자(최대 4기가 바이트)
    - BLOB : Binary Data

# 3. FUNCTION

- 그룹 함수
    - 그룹 함수는 1행 이상의, RESULT SET의 결과가 1행인 함수이다.
    - SUM(숫자가 기록 된 컬럼명): 합계를 구하여 리턴
    - AVG(숫자가 기록 된 컬럼명): 평균을 구하여 리턴 
    AVG(DISTINCT 숫자가 기록된 컬럼명): 중복값을 제외하여 구한 평균을 리턴
    - AVG(NVL (숫자가 기록된 컬럼명, 0): NULL값을 0으로 취급한 뒤 평균을 구하여 리턴
    - MIN(숫자가 기록 된 컬럼명): 최솟값을 구하여 리턴
    - MAX(숫자가 기록 된 컬럼명): 최댓값을 구하여 리턴
    - COUNT(*|컬럼명): 행의 갯수를 리턴
    COUNT(*): NULL을 포함한 전체 행 갯수 리턴
    COUNT(컬럼명): 컬럼값이 NULL이 아닌 행 갯수 리턴
- 단일행 함수
    - LENGTH, LENGTHB: 문자열의 숫자와 문자열의 바이트 리턴
    - INSTR(’문자열’ | 컬럼명, ‘문자’, 찾을 위치의 시작값, (빈도)): 문자열(컬럼명) 안에서 ‘문자’가 어디에 있는지 확인하여 리턴. 
    찾을 위치의 시작값은 양수는 왼쪽에서 오른쪽, 음수는 오른쪽에서 왼쪽으로 계산한다.
    - SUBSTR: 지정한 위치에서 지정한 길이만큼 문자를 잘라내어 리턴
    - SUBSTRB: 지정한 위치에서 지정한 길이만큼 바이트를 잘라내어 리턴
        
        ```sql
        SELECT
               EMAIL
             , SUBSTR(EMAIL, 1, INSTR(EMAIL, '@') -1)
             , SUBSTR(EMAIL, INSTR(EMAIL, '@') +1, 5)       -- SUBSTR(컬럼명, 시작위치, 길이)
          FROM EMPLOYEE;
        ```
        
    - LTRIM / RTRIM: 주어진 컬럼이나 문자열 왼쪽/오른쪽에서 지정한 문자 혹은 문자열을 제거, 나머지를 반환하는 함수. (왼쪽, 오른쪽)
        
        ```sql
        SELECT LTRIM('ACABACCTHE', 'ABC') FROM DUAL;
        SELECT RTRIM('THEACABACC', 'ABC') FROM DUAL;
        ```
        
    - TRIM: 주어진 컬럼이나 문자열의 앞/뒤에 지정한 문자를 제거한다. LEADING은 왼쪽, TRAILING은 오른쪽, BOTH는 양쪽.
        
        ```sql
        SELECT TRIM('    THE     ') FROM DUAL;
        SELECT TRIM('Z' FROM 'ZZZ123456ZZZ') FROM DUAL;
        SELECT TRIM(LEADING 'Z' FROM 'ZZZTHEZZZ') FROM DUAL;
        SELECT TRIM(TRAILING '3' FROM '333THE333') FROM DUAL;
        SELECT TRIM(BOTH '3' FROM '333THE333') FROM DUAL;
        ```
        
    - LPAD/RPAD: 주어진 컬럼 문자열에 임의의 문자열을 덧붙여 같이 N의 문자열을 반환하는 함수.
        
        ```sql
        SELECT 
               LPAD(EMAIL, 20, '#') 
          FROM EMPLOYEE;
        ```
        
    - LOWER/UPPER/INITCAP: 대소문자로 변경해 주는 함수
        
        ```sql
        SELECT
               LOWER('Welcome to My World') 
        		 , UPPER('Welcome to My World')
        		 , INITCAP('welcome to my world')			
          FROM DUAL;
        ```
        
    - CONCAT: 문자열 혹은 컬럼 두 개를 입력 받아 하나로 합친 후 리턴
        
        ```sql
        SELECT
               CONCAT('가나다라', 'ABCD') "CONCAT"
             , '가나다라' || 'ABCD' "||"
          FROM DUAL;
        ```
        
    - REPLACE: 컬럼 혹은 문자열을 입력받아 변경하고자 하는 문자열을 변경하고자 하는 문자열로 바꾼 후 처리.
        
        ```sql
        SELECT
               REPLACE('서울시 강남구', '강남구', '서초구') 
          FROM DUAL;
        ```
        

<aside>
💡 그룹함수와 단일행함수를 같이 사용시, 계산해야 하는 컬럼의 숫자 차이로 인한 에러가 발생한다. → 단일 그룹의 그룹 함수가 아닙니다.
→ GROUP BY 필요

</aside>

- 숫자 처리 함수
    - ABS(숫자||숫자로 된 컬럼명) : 절대값 구하는 함수
        
        ```sql
        SELECT
               ABS(-10)
             , ABS(10) 
          FROM DUAL;
        ```
        
    - MOD(숫자||숫자로 된 컬럼명, 숫자||숫자로 된 컬럼명): 두 수를 나누어 나머지를 구하는 함수
        
        ```sql
        SELECT
               MOD(10, 0)
             , MOD(10, 3)
          FROM DUAL;
        ```
        
    - ROUND(숫자||숫자로 된 컬럼명, [위치]) : 반올림 해서 리턴하는 함수 
    양수와 음수로 .을 기준으로 반올림 할 위치를 정한다.
        
        ```sql
        SELECT ROUND(123.456, 1) FROM DUAL;
        SELECT ROUND(123.456, 2) FROM DUAL;
        SELECT ROUND(123.456, -1) FROM DUAL;
        ```
        
    - FLOOR(숫자||숫자로 된 컬럼명): 내림처리 하는 함수
        
        ```sql
        SELECT FLOOR(123.456) FROM DUAL;
        SELECT FLOOR(123.678) FROM DUAL;
        ```
        
    - TRUNC(숫자||숫자로 된 컬럼명, [위치]): 내림처리 하는 함수
    .을 기준으로 양수와 음수로 내림처리 할 위치를 정한다
        
        ```sql
        SELECT TRUNC(123.456, 2) FROM DUAL;
        SELECT TRUNC(123.456, -1) FROM DUAL;
        ```
        
    - CEIL(숫자||숫자로 된 컬럼명): 올림처리 함수
        
        ```sql
        SELECT CEIL(123) FROM DUAL;
        SELECT CEIL(123.456) FROM DUAL;
        ```
        
- 날짜 처리 함수
    - SYSDATE: 현재 날짜(시스템 시간 기준)을 구하는 함수
        
        ```sql
        SELECT SYSDATE FROM DUAL;
        ```
        
    - MONTHS_BETWEEN(날짜, 날짜): 두 날짜의 개월 수 차이를 숫자로 리턴하는 함수
        
        ```sql
        SELECT
        			 FLOOR(MONTHS_BETWEEN(SYSDATE, HIRE_DATE))
          FROM EMPLOYEE;
        ```
        
    - ADD_MONTHS(날짜, 숫자): 날짜에 숫자만큼의 개월 수를 더해서 리턴
        
        ```sql
        SELECT
               ADD_MONTHS(SYSDATE, 5) 
          FROM DUAL;
        ```
        
    - NEXT_DAY(기준 날짜, 요일(문자||숫자)):  기준날짜로부터 요일에 해당하는 날짜를 리턴.
        
        ```sql
        SELECT SYSDATE, NEXT_DAY(SYSDATE, '목요일') FROM DUAL; 
        SELECT SYSDATE, NEXT_DAY(SYSDATE, 5) FROM DUAL;
        SELECT SYSDATE, NEXT_DAY(SYSDATE, '목') FROM DUAL;
        ```
        
    - LAST_DAY(날짜) : 해당 날짜의 월에서 마지막 날짜를 구하여 리턴.
        
        ```sql
        SELECT
               SYSDATE
             , LAST_DAY(SYSDATE) 
             , LAST_DAY('1987/02/02')
          FROM DUAL;
        ```
        
    - EXTRACT: DATE형에서 년, 월, 일 정보를 추출하여 리턴하는 함수
        
        ```sql
        SELECT
               EXTRACT(YEAR FROM SYSDATE)
             , EXTRACT(MONTH FROM SYSDATE)
             , EXTRACT(DAY FROM SYSDATE)
          FROM DUAL;
        ```
        
- 형변환 함수
    - TO_CHAR(날짜||숫자, [포멧]): 날짜,숫자형 데이터를 문자형 데이터로 변경
        
        ```sql
        SELECT TO_CHAR(1234, 'L99999') FROM DUAL;        // 지역금액으로 변환. (￦1234)
        SELECT TO_CHAR(1234, 'L000,000,000') FROM DUAL;  // 000,001,234로 반환
        ```
        
    - 날짜 데이터 포멧
        
        ```sql
        SELECT TO_CHAR(SYSDATE, 'PM HH24;MI;SS') FROM DUAL;
        SELECT TO_CHAR(SYSDATE, 'AM HH24:MI;SS') FROM DUAL;
        SELECT TO_CHAR(SYSDATE, 'MON DY, YYYY') FROM DUAL; 
        SELECT TO_CHAR(SYSDATE, 'YYYY-fmMM-DD DAY') FROM DUAL;  
        SELECT TO_CHAR(SYSDATE, 'YYYY-MM-DD DAY') FROM DUAL; 
        SELECT TO_CHAR(SYSDATE, 'YEAR, Q') || '분기' FROM DUAL;
        ```
        
        <aside>
        💡 RR은 두 자리로 인식한 년도를 네자리로 바꿀 때 
        바꿀 년도가 50년 미만(00~49)이면 2000년대를 적용하고
        50년 이상(50~99)이면 1900년대를 적용한다.
        
        </aside>
        
- 선택 함수
    - DECODE(계산식||컬럼명, 조건값1, 선택값1, 조건값2, 선택값2...): 여러 가지 경우에 선택할 수 있는 기능을 제공. 
    마지막 인자로 조건값 없이 선택값만 작성하면, 어떤 조건 값에도 해당하지 않는 경우의 값들이 선택된다.
        
        ```sql
        SELECT
               EMP_ID
             , EMP_NAME
             , EMP_NO
             , DECODE(SUBSTR(EMP_NO, 8, 1), '1', '남', '2', '여') 성별
          FROM EMPLOYEE;
        ```
        
    - CASE ~ END: 조건식 기능. JAVA의 SIWTCH문과 유사하다. 단, 조건식 부분에 조건문을 삽입 역시 가능하다.
        
        ```sql
        SELECT
               EMP_ID
             , EMP_NAME
             , SALARY
             , CASE 
                WHEN SALARY > 5000000 THEN '고급'
                WHEN SALARY > 3000000 AND SALARY <= 5000000 THEN '중급'
                WHEN SALARY <= 3000000 THEN '초급'
               END 구분
          FROM EMPLOYEE;
        ```
        
- 집계 함수
    - ROLLUP 함수: 그룹별로 중간 집계 처리를 하는 함수. GROUP BY 절에서만 사용하는 함수며, 그룹별로 묶여진 값에 대한 중간 집계와 총 집게를 구할 때 사용한다.
        
        ```sql
        SELECT
               JOB_CODE   
             , SUM(SALARY) 
          FROM EMPLOYEE
         GROUP BY ROLLUP(JOB_CODE);
        
        SELECT
               DEPT_CODE
             , JOB_CODE
             , SUM(SALARY)
          FROM EMPLOYEE
         GROUP BY ROLLUP(DEPT_CODE, JOB_CODE);
        ```
        
    - CUBE함수: 그룹별 산출한 결과를 집게하는 함수로, ROLLUP함수와 거의 유사하다. 단, 전달 되는 컬럼명이 다수일 때는 차이가 있다.
        - CUBE는 인자가 추가될 때마다 별도로 ROLLUP을 실행한다.
        
        ```sql
        SELECT
               JOB_CODE   
             , SUM(SALARY) 
          FROM EMPLOYEE
         GROUP BY CUBE(JOB_CODE)
         ORDER BY 1 NULLS LAST;
        
        SELECT
               DEPT_CODE
             , JOB_CODE
             , SUM(SALARY)
          FROM EMPLOYEE
         GROUP BY CUBE(DEPT_CODE, JOB_CODE);
        ```
        
    - GROUPING 함수: ROLLUP이나 UCBE에 의한 산출물이 인자로 전달받은 컬럼 집합의 산출물이면 0을, 아니면 1을 반환하는 함수. 단, 0과 1로는 가독성이 떨어지기 때문에 CASE문을 같이 활용하는 경우가 많다.
        
        ```sql
        SELECT
               DEPT_CODE
             , JOB_CODE
             , SUM(SALARY)
             , CASE
                WHEN GROUPING(DEPT_CODE) = 0 AND GROUPING(JOB_CODE) = 1 THEN '부서별합계'
                WHEN GROUPING(DEPT_CODE) = 1 AND GROUPING(JOB_CODE) = 0 THEN '직급별합계'
                WHEN GROUPING(DEPT_CODE) = 1 AND GROUPING(JOB_CODE) = 1 THEN '총합계'
                END
          FROM EMPLOYEE 
         GROUP BY CUBE(DEPT_CODE, JOB_CODE)
         ORDER BY 1;
        ```
        
        ![KakaoTalk_20220514_222339597.jpg](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/KakaoTalk_20220514_222339597.jpg)
        
- 집합 연산
    - SET OPERATOR
    - UNION: 여러 개의 쿼리 결과 튜플들을 하나로 합치는 연산자. 중복된 영역은 제외한다. (합집합)
        
        ```sql
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE DEPT_CODE = 'D5'
         UNION
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE SALARY > 3000000;
        ```
        
    - UNION ALL: 여러 개의 쿼리 결과 튜플들을 하나로 합치는 연산자. 중복 영역을 모두 포함한다.
        
        ```sql
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE DEPT_CODE = 'D5'
         UNION ALL
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE SALARY > 3000000
         ORDER BY 2;
        ```
        
    - INTERSECT: 여러 개의 SELECT한 결과에서 공통 부분만 결과로 추출하는 연산자. (교집합)
        
        ```sql
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE DEPT_CODE = 'D5'
        INTERSECT
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE SALARY > 3000000
         ORDER BY 2;
        ```
        
    - MINUS: 선행 SELECT 쿼리 결과 튜플들에서 다음 SELECT한 결과 튜플과 겹치는 부분을 제외한 나머지 부분만 추출 (차집합). SELECT문 선서에 주의해야 한다.
        
        ```sql
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE DEPT_CODE = 'D5'
         MINUS
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE SALARY > 3000000
         ORDER BY 2;
        ```
        
    - GROUPING SETS: 그룹별로 처리 된 여러 개의 SELECT문을 하나로 합칠 때 사용한다. (그룹을 묶는 기준이 다양한 경우, 한 번에 조회하고자 할 때 사용한다.)
        
        ```sql
        SELECT
               DEPT_CODE
             , JOB_CODE
             , MANAGER_ID
             , FLOOR(AVG(SALARY))
          FROM EMPLOYEE
         GROUP BY GROUPING SETS((DEPT_CODE, JOB_CODE, MANAGER_ID)
                             , (DEPT_CODE, MANAGER_ID)
                             , (JOB_CODE, MANAGER_ID))
         ORDER BY 1;
        ```
        
- 사용자 정의 함수
    - PROCEDURE와 사용 용도는 거의 유사하다. 차이점은 RETURN을 통해 실행 결과를 되돌려 받을 수 있다는 점.
    - 객체이기에 CREATE를 통해 생성한다.
        
        ```sql
        CREATE OR REPLACE FUNCTION BONUS_CALC
        (
          V_EMP EMPLOYEE.EMP_ID%TYPE
        )
        RETURN NUMBER
        IS
         V_SAL EMPLOYEE.SALARY%TYPE;
         V_BONUS EMPLOYEE.BONUS%TYPE;
         CALC_SAL NUMBER;
        BEGIN
          SELECT A.SALARY
               , NVL(A.BONUS, 0)
            INTO V_SAL
               , V_BONUS 
            FROM EMPLOYEE A 
           WHERE A.EMP_ID = V_EMP;
           
           CALC_SAL := V_SAL * (1 + V_BONUS) * 12;
           
           RETURN CALC_SAL;
        END;
        /
        ```
        
    - 매개변수로 EMPLOYEE.EMP_ID%TYPE을 받아 V_EMP 로 지정한다.
    - 그 후 RETURN 값인 NUMBER 변수를 V_SAL, V_BONUS, CALC_SAL로 선언한다.
    - 이후 BEGIN 문을 통해 각 변수값을 초기화 한 뒤
    - CALC_SAL :=을 통해 함수를 정의한다.
    - 이후 리턴값으로 CALC_SAL을 지정, 이후 BONUS_CALC 함수를 호출하면 RETURN 값인 CALC_SAL이 사용된다.
    - 해당 함수 역시 기존에 정의되어 있던 단일행 함수 (SUBSTR같은) 처럼 SELECT문에서 활용 가능하다.
- 윈도우 함수
    
    ![Untitled](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/Untitled.png)
    
    ![Untitled](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/Untitled%201.png)
    
    ![Untitled](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/Untitled%202.png)
    
    ![Untitled](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/Untitled%203.png)
    
    ![Untitled](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/Untitled%204.png)
    
    ![Untitled](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/Untitled%205.png)
    

# 4. SQL Commands

![1.png](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/1%201.png)

- DDL(Data Definition Language)
    - 정의
        - 데이터 정의 언어: 객체를 만들고 수정하고, 삭제하는 구문
        - 오라클에서의 객체는
            
            테이블(TABLE), 뷰(VIEW), 시퀸스(SEQUENCE)
            인덱스(INDEX), 패키지(PACKAGE), 트리거(TRIGGER),
            동의어(SYNONYM), 프로시저(PROCEDURE), 함수(FUNCTION),
            사용자(USER)
            가 있다.
            
    - CREATE
        - SYSTEM 계정에서 각각의 계정을 생성 및 권한 부여를 할 수 있다.
            
            ```sql
            CREATE USER C##EMPLOYEE IDENTIFIED BY EMPLOYEE;
            -- C##EMPLOYEE라는 이름의, EMPLOYEE 비밀번호를 가진 계정 생성
            ```
            
        - 테이블의 생성은 아래와 같은 방식으로 진행된다.
            
            ```sql
            CREATE TABLE MEMBER(
              MEMBER_ID VARCHAR2(20),
              MEMBER_PWD VARCHAR2(20),
              MEMBER_NAME VARCHAR2(20)
            );
            ```
            
        - CREATE 시 서브쿼리를 이용하여 테이블을 생성할 수 있다. 
        이 때, WHERE 절에 FALSE 값을 넣음으로서 값은 제외하고 형식만 넣을 수 있다.
            
            ```sql
            CREATE TABLE EMP_DEPT_D1
            AS
            SELECT A.EMP_ID
                 , A.EMP_NAME
                 , A.DEPT_CODE
                 , A.HIRE_DATE
              FROM EMPLOYEE A
             WHERE 1 = 0;
            ```
            
    - DROP (TRUNCATE)
        - DROP을 통해 테이블을 삭제할 수 있다.
            
            ```sql
            DROP TABLE MEMBER;
            ```
            
        - TRUNCATE를 통해 테이블의 전체 행을 삭제할 수 있다. 단, DELETE와 달리 ROLLBACK을 통해 복구가 불가능하며, 수행 속도가 조금 더 빠르다.
            
            ```sql
            TRUNCATE TABLE EMP_SALARY;
            ```
            
    - ALTER
        - ALTER: 객체를 수정하는 구문.
        - ALTER를 활용 해 컬럼을 추가(ADD) / 삭제(DROP) / 변경(MODIFY)할 수 있다.
        단, NOT NULL은 추가 / 삭제에도 MODIFY를 사용한다. 
        단, 컬럼은 최소 한 개는 남아있어야 한다. (마지막 컬럼은 삭제 불가능)
            
            ```sql
            ALTER TABLE DEPT_COPY
            ADD (LNAME VARCHAR2(20));
            -- 컬럼 추가
            
            ALTER TABLE DEPT_COPY
            DROP COLUMN LNAME;
            -- 컬럼 삭제
            
            ALTER TABLE DEPT_COPY2
            MODIFY DEPT_ID CHAR(3);
            -- 컬럼 자료형 수정
            
            ALTER TABLE DEPT_COPY
            MODIFY CNAME DEFAULT '미국';
            -- 컬럼 DEFAULT 값 수정 (기존의 데이터는 변경되지 않고, 추가된 데이터에 적용된다.)
            
            ALTER TABLE DEPT_COPY3
            RENAME COLUMN DEPT_ID TO DEPT_CODE;
            -- 컬럼 이름 변경
            ```
            
        - ALTER를 활용해 테이블과 컬럼에서 규정하지 않은 제약조건을 추가 / 삭제 / 변경 할 수 있다. 단, 컬럼이 제약조건에 위배되지 않을 때만 가능하다.
            
            ```sql
            ALTER TABLE 테이블명 ADD PRIMARY KEY(컬럼명)
            ALTER TABLE 테이블명 ADD FOREIGN KEY(컬럼명)
                                     REFERENCES 테이블명 (컬럼명)
            ALTER TABLE 테이블명 ADD UNIQUE(컬럼명)
            ALTER TABLE 테이블명 ADD CHECK(컬럼명 비교연산자 비교값)
            ALTER TABLE 테이블명 MODIFY 컬럼명 NOT NULL
            
            -- 제약 조건 추가
            
            ALTER TABLE CONST_EMP
            DROP CONSTRAINT FK_DID
            DROP CONSTRAINT FK_JID
            DROP CONSTRAINT FK_MID;
            -- 제약 조건 삭제
            
            ALTER TABLE DEPT_COPY3
            RENAME CONSTRAINT PK_DEPT_CODE3 TO PK_DCODE;
            -- 제약 조건 이름 변경
            
            ALTER TABLE EMPLOYEE
            DISABLE CONSTRAINT SYS_C007959;
            -- 제약 조건 끄기
            
            ALTER TABLE EMPLOYEE
            ENABLE CONSTRAINT SYS_C007959;
            -- 제약 조건 다시 켜기
            ```
            
        - ALTER를 이용해 테이블 역시 변경, 삭제할 수 있다.
            
            ```sql
            ALTER TABLE DEPT_COPY3
            RENAME TO DEPT_TEST;
            -- 테이블 이름 변경
            
            DROP TABLE DEPT_TEST CASCADE CONSTRAINTS;
            -- 테이블 삭제
            (CASCADE 활용 시 참조 테이블이 있어도 삭제 가능)
            ```
            
- DML(Data Manipulation Language)
    - 정의
        - 데이터 조작언어. 테이블에 값을 삽입하거나, 수정하거나, 삭제하거나, 조회하는 언어.
    - INSERT (ALL)
        - INSERT - VALUES를 통해 생성된 테이블에 컬럼을 삽입할 수 있다.
            
            ```sql
            INSERT
              INTO USER_NOCONS
             (
              USER_NO, USER_ID, USER_PWD
            , USER_NAME, GENDER, PHONE
            , EMAIL
             )
             VALUES
             (
              1
            , 'USER01', 'PASS01'
            , '홍길동', '남', '010-1234-5678'
            , 'hong123@greedy.or.kr'
             );
            ```
            
        - INSERT ~ VALUES를 통해 DATA 삽입 시,  VALUES 대신 서브쿼리를 응용할 수 있다.
            
            ```sql
            INSERT
              INTO EMP_01
            (
              SELECT A.EMP_ID
                   , A.EMP_NAME
                   , B.DEPT_TITLE
                FROM EMPLOYEE A
                LEFT JOIN DEPARTMENT B ON(A.DEPT_CODE = B.DEPT_ID)
            );
            ```
            
        - INSERT ALL: INSERT 시에 사용하는 서브쿼리가 같은 경우, 두 개 이상의 테이블에 INSERT ALL을 이용하여 한 번에 데이터를 삽입할 수 있다. 단, 서브쿼리의 조건절이 같아야 한다. 
        이 때, WHEN~THEN을 응용하여 조건을 넣을 수도 있다.
            
            ```sql
            INSERT ALL
              WHEN HIRE_DATE < TO_DATE('2000/01/01', 'RRRR/MM/DD')
              THEN
              INTO EMP_OLD
             VALUES 
            (
              EMP_ID
            , EMP_NAME
            , HIRE_DATE
            , SALARY
            )
              WHEN HIRE_DATE >= TO_DATE('2000/01/01', 'RRRR/MM/DD')
              THEN
              INTO EMP_NEW
            VALUES 
            (
              EMP_ID
            , EMP_NAME
            , HIRE_DATE
            , SALARY
            )
            SELECT 
                   A.EMP_ID
                 , A.EMP_NAME
                 , A.HIRE_DATE
                 , A.SALARY
              FROM EMPLOYEE A;
            ```
            
    - UPDATE
        - UPDATE: 테이블에 기록된 컬럼의 값을 수정하는 구문으로, 전체 행 갯수는 변화가 없다.
        UPDATE 테이블명 SET 컬럼명 = 바꿀값, 컬럼명 = 바꿀값, ...
        [WHERE 컬럼명 비교연산자 비교값]
        형식으로 사용한다.
            
            ```sql
            UPDATE
                   EMP_SALARY A
               SET (A.SALARY, A.BONUS) = (SELECT B.SALARY
                                               , B.BONUS
                                            FROM EMP_SALARY B
                                           WHERE B.EMP_NAME = '유재식'         
                                          ) 
             WHERE A.EMP_NAME IN ('노옹철', '전형돈', '정중하', '하동운');
            ```
            
    - DELETE
        - DELETE를 통해 생성한 테이블을 삭제 할 수 있다.
        삭제 시 테이블 행의 갯수가 줄어든다.
        특정 값(VALUES)을 삭제하고 싶다면 WHERE절을 응용할 수 있다.
        (설정하지 않으면 모든 행이 다 삭제된다.)
            
            ```sql
            DELETE
              FROM EMPLOYEE A
             WHERE A.EMP_NAME = '하이유';
            ```
            
    - COMMENT
        - 컬렘에 COMMENT를 통해 주석을 삽입할 수 있다.
            
            ```sql
            COMMENT ON COLUMN MEMBER.MEMBER_ID IS '아이디'; 
            COMMENT ON COLUMN MEMBER.MEMBER_PWD IS '비밀번호'; 
            COMMENT ON COLUMN MEMBER.MEMBER_NAME IS '이름';
            ```
            
    - MERGE
        - MERGE: 구조가 같은 두 개의 테이블을 하나의 테이블을 기준으로 테이블에서 지정하는 조건의 값이 존재하면 UPDATE, 조건의 값이 없으면 INSERT가 된다.
            
            ```sql
            MERGE
              INTO EMP_NO1 A
             USING EMP_NO2 B
                ON (A.EMP_ID = B.EMP_ID)
              WHEN MATCHED THEN
            UPDATE
               SET A.EMP_NAME = B.EMP_NAME
                 , A.EMP_NO = B.EMP_NO
                 , A.EMAIL = B.EMAIL
                 , A.PHONE = B.PHONE
                 , A.DEPT_CODE = B.DEPT_CODE
                 , A.JOB_CODE = B.JOB_CODE
                 , A.SAL_LEVEL = B.SAL_LEVEL
                 , A.SALARY = B.SALARY
                 , A.BONUS = B.BONUS
                 , A.MANAGER_ID = B.MANAGER_ID
                 , A.HIRE_DATE = B.HIRE_DATE
                 , A.ENT_DATE = B.ENT_DATE
                 , A.ENT_YN = B.ENT_YN
              WHEN NOT MATCHED THEN   
            INSERT
            (
              A.EMP_ID, A.EMP_NAME, A.EMP_NO, A.EMAIL, A.PHONE
            , A.DEPT_CODE, A.JOB_CODE, A.SAL_LEVEL, A.SALARY, A.BONUS
            , A.MANAGER_ID, A.HIRE_DATE, A.ENT_DATE, A.ENT_YN
            )
            VALUES
            (
              B.EMP_ID, B.EMP_NAME, B.EMP_NO, B.EMAIL, B.PHONE
            , B.DEPT_CODE, B.JOB_CODE, B.SAL_LEVEL, B.SALARY, B.BONUS
            , B.MANAGER_ID, B.HIRE_DATE, B.ENT_DATE, B.ENT_YN
            );
            ```
            
        
- TCL(Transaction Control Language)
    - 정의
        - 데이터의 보안/무결성/회복/병행 제어 등을 정의하는데 사용한다.
        - Transaction : 한꺼번에 수행되어야 할 최소의 작업 단위 (논리적 작업 단위, LUW(Logical Unit of Work))
    - COMMIT
        - 트랜잭션 작업이 정상 완료되면 변경 내용을 영구히 저장한다.
        - COMMIT을 통해 수정된 데이터들을 저장할 수 있다.
            
            ```sql
            COMMIT;
            ```
            
    - SAVEPOINT
        - SAVEPOINT 세이브포인트명
        형태로 사용한다.
        - 현재 트랜잭션 작업 시점에 이름을 정해준다. (하나의 트랜잭션 안에 구역을 나눈다.)
            
            ```sql
            SAVEPOINT NAME;
            -- 으로 지정한다.
            ```
            
    - ROLLBACK
        - 트랜잭션 작업을 취소하고 최근 COMMIT한 시점으로 이동한다.
        DB에서는 UPDATE, INSERT, DELETE 단위(DML단위)로 돌아가기도 한다.
        ★ UPDATE를 1번하건, 10번하건, 다른 작업을 하기 전까지를 모두 하나의 트랜잭션 단위로 여긴다.
        - ROLLBACK; 을 통해 커밋되지 않은 데이터를 취소할 수 있다.
            
            ```sql
            ROLLBACK;
            ```
            
        - ROLLBACK TO 세이브포인트명
        의 형태로 현재 트랜잭션 작업을 취소하고 SAVEPOINT 시점으로 이동한다.
            
            ```sql
            ROLLBACK NAME;
            --으로 세이브포인트 시점으로 되돌린다.
            ```
            
- DQL(Data Query Language**)**
    - 정의
        - 데이터에 들어있는 정보들을 조회하거나 검색하기 위한 명령어. DML의 한 분류로 보기도 한다.
    - SELECT
        
        <aside>
        💡 연산자 우선순위 
        1. 산술연산자
        2. 연결연산자(||)
        3. 비교연산자
        4. IS/IS NOT NULL, LIKE/NOT LIKE, IN/NOT IN
        5. BETWEEN AND/NOT BETWEEN AND
        6. NOT
        7. AND
        8. OR
        
        </aside>
        
        - TABLE에서 특정 컬럼을 뽑아내고 싶을 때(READ, SELECT)는 아래와 같이 사용한다.
            
            ```sql
            SELECT               
                   EMP_ID
                 , EMP_NAME
              FROM EMPLOYEE;
            ```
            
        - TABLE에서 특정 조건의 튜플을 조회하고 싶을 때는 아래와 같이 사용한다.
        WHERE 구문은 JAVA의 IF와 그 성질이 비슷하다.
        조건을 여러개 걸고 싶을 때는 AND, OR을 사용한다.
        AND 사용시, 앞 조건에 일치하는 결과가 없다면 뒷 조건은 계산하지 않는다.
            
            ```sql
            SELECT
                   *
              FROM EMPLOYEE
             WHERE DEPT_CODE = 'D9' AND SALARY > '300000';
            ```
            
        - 튜플과 컬럼을 동시에 조건으로 걸고 싶다면 아래와 같다.
            
            ```sql
            SELECT
                   EMP_ID
                 , EMP_NAME
                 , DEPT_CODE
                 , SALARY
              FROM EMPLOYEE
             WHERE SALARY >= '3000000';
            ```
            
        - 컬럼을 컬럼의 이름이 아닌 사용자가 지정한 이름으로 부르고 싶을 때는 공백 후 입력하는 내용으로 계산한다. 단, 이는 컬럼의 이름이 바뀌는 것이 아닌, 변수와 같은 기능을 한다.
        AS를 붙이는 것이 정석이지만, 생략 가능하다.
            
            ```sql
            SELECT
                   EMP_NAME 이름
                 , SALARY * 12 AS "1년 연봉(원)" 
              FROM EMPLOYEE;
            ```
            
        - NVL(NULLVALUE) : NULL을 VALUE로 변환한다
            
            ```sql
            SELECT
                   EMP_ID
                 , NVL(BONUS, 0) AS "NVL 처리" 
              FROM EMPLOYEE;
            ```
            
        - DISTINCT : SELECT 절에서 한 번만 사용 가능하다. 해당 컬럼의 중복값을 제거 가능하다.
            
            ```sql
            SELECT
                   DISTINCT JOB_CODE
              FROM EMPLOYEE;
            ```
            
        - || : 연결연산자를 이용하여 여러 컬럼을 하나의 컬럼인 것처럼 연결할 수 있다.
            
            ```sql
            SELECT
                   EMP_ID || EMP_NAME || SALARY "한방에 보는 컬럼" 
              FROM EMPLOYEE;
            
            SELECT
                   EMP_NAME || '의 컬럼은' || SALARY || '원 입니다.' 
              FROM EMPLOYEE;
            ```
            
        - 비교연산자는 =, >, >=, <, <=의 비교와 !=, ^=, <>의 같지 않은가의 비교가 가능하다. 
        BETWEEN AND 구문을 사용, 이상 이하의 값을 구할 수 있다.
            
            ```sql
            SELECT
                   EMP_ID
                 , EMP_NAME
                 , SALARY
                 , DEPT_CODE
                 , JOB_CODE
              FROM EMPLOYEE
             WHERE SALARY BETWEEN '3500000' AND '5000000';
            ```
            
        - LIKE 연산자를 통해 문자 패턴이 일치하는 값을 조회할 수 있다.
        - %와 _ 연산자(와일드카드)를 사용한다.
            
            ```sql
            SELECT
                   EMP_ID
                 , EMP_NAME
                 , HIRE_DATE
              FROM EMPLOYEE
             WHERE EMP_NAME LIKE '김%';
            
            SELECT
                   EMP_ID
                 , EMP_NAME
                 , PHONE
              FROM EMPLOYEE
             WHERE PHONE LIKE '___9%';
            ```
            
        - ESCAPE ‘VAR’  : 1바이트 문자열을 ESCAPE 변수로 지정 후 사용한다. 와일드카드 문자를 문자 데이터로 처리하기 위해 사용한다.
            
            ```sql
            SELECT
                   EMP_ID
                 , EMP_NAME
                 , EMAIL
              FROM EMPLOYEE
             WHERE EMAIL LIKE '___#_%' ESCAPE '#';
            ```
            
        - IN : 비교하려는 값 목록에 일치하는 값이 있는지 확인할 때 사용한다.
            
            ```sql
            SELECT
                   EMP_NAME 
                 , DEPT_CODE
                 , SALARY
              FROM EMPLOYEE
             WHERE DEPT_CODE IN ('D6', 'D8', 'D9');
            ```
            
        
    - GROUP BY 절
        - 그룹함수는 단 한 개의 결과값만 산출하기 때문에, 그룹함수를 이용하여 여러 개의 결과값을 산출하기 위해서는 그룹함수가 적용될 그룹의 기준을 GROUP BY절에 기술하여 사용해야 한다.
        - 여러 개의 그룹을 기준으로 삼을 때는 ,를 이용한다.
        
        ```sql
        SELECT 
        			 DEPT_CODE
        		 , SUM(SALARY)	
          FROM EMPLOYEE
         GROUP BY DEPT_CODE;
        
        SELECT
               DEPT_CODE
             , JOB_CODE 
             , COUNT(*) 
          FROM EMPLOYEE
         GROUP BY DEPT_CODE
                , JOB_CODE
         ORDER BY 1 ASC, 2 DESC;
        ```
        
    - HAVING
        - 그룹함수를 구해 올 그룹에 대해 조건을 설정할 때 사용한다. 
        HAVING 컬럼명 | 함수식(그룹함수) 비교연산자 비교값
        의 형태를 이용한다.
        
        ```sql
        SELECT 
               DEPT_CODE 
             , FLOOR(AVG(SALARY))
          FROM EMPLOYEE  
         GROUP BY DEPT_CODE   
        HAVING FLOOR(AVG(SALARY)) >= 3000000 
         ORDER BY DEPT_CODE;
        ```
        
    - ORDER BY 절
        - SELECT한 컬럼에 대해 정렬을 할 때 작성하는 구문.
        - SELECT 구문의 가장 마지막에 작성하며, 실행순서도 가장 마지막이다.
        
        ```sql
        ORDER BY 컬럼명|별칭|컬럼순번 정렬방식[NULLS FIRST|LAST]
        ```
        
- DCL(Data Control Language)
    - 정의
        - 데이터베이스에 접근하고, 객체들을 사용하도록 권한을 부여, 회수하는 명령어
    - GRANT
        - GRANT를 통해 권한을 부여할 수 있다.
            
            ```sql
            GRANT CONNECT, RESOURCE, CREATE VIEW TO C##EMPLOYEE;
            -- C##EMPLOYEE에 CONNECT, RESOURCE, CREATE VIEW 권한 부여
            ```
            
        - WITH ADMIN OPTION: 사용자에게 시스템 권한을 부여할 때 사용한다. 권한을 부여 받은 사용자는 다른 사용자에게도 해당 권한을 지정할 수 있다.
        - WITH GRANT OPTION: 사용자가 특정 객체를 조작하거나 접근 할 수 있는 권한을 부여 한다. 그 권한을 다른 사용자에게 해당 사용자가 다시 부여할 수 있다.
        - 부여할 수 있는 권한 목록은 다음과 같다.
        시스템 권한: 
        1. CREATE USER(사용자 계정 만들기)
        2. DROP USER(사용자 계정 삭제)
        3. DROP ANY TABLE(임의의 테이블 삭제)
        4. QUERY REWRITE(함수 기반 인덱스 생성 권한)
        5. BACKUP ANY TABLE(테이블 백업)
        6. CREATE SESSION (데이터 베이스 접속 권한)
        7. CREATE TABLE(테이블 생성)
        8. CREATE VIEW(뷰 생성)
        9. CREATE SEQUENCE(시퀸스 생성)
        10. CREATE SYNONYM(동의어 생성)
        11. CREATE PROCEDURE(프로시저 생성)
        - 객체 권한: 사용자가 특정 객체(테이블, 뷰, 시퀸스, 함수)를 조작하거나 접근할 수 있는 권한.
        객체를 DML 처리하는 권한 종류
            
            ALTER TABLE, SEQUENCE
            DELETE TABLE, VIEW
            EXECUTE, PROCEDURE
            NDEX TABLE
            REFERENCES TABLE
            INSERT TABLE, VIEW
            SELECT TABLE, VIEW, SEQUENCE
            UPDATE TABLE, VIEW
            
    - REVOKE
        - GRANT를 통해 부여된 권한을 회수할 수 있다.
            
            ```sql
            REVOKE SELECT ON C##USERNAME.TABLENAME FROM C##USERNAME;
            -- 앞의 USERNAME에 부여된 권한을 뒤의 USERNAME에서 회수한다.
            ```
            
            <aside>
            💡 WITH GRANT OPTION은 REVOKE시 다른 사용자에게 부여된 권한도 모두 회수한다.
            WITH ADMIN OPTION은 해당 사용자의 권한만 회수된다.
            
            </aside>
            
    - ROLL
        - 권한을 묶어서 간편하게 권한 부여를 하기 위한 방법.  오라클에서 사전 정의된 롤과 사용자가 정의하는 롤이 있다.
        - 사전 정의된 롤은 CONNECT, RESOURCE와 같은 것.
        - 사용자가 정의하는 롤은 아래와 같은 방법으로 생성한다.
        1. CREATE ROL ROLLNAME;    — 롤 생성
        2. GRANT 권한종류 TO ROLLNAME; — 생성된 롤에 권한 부여
        3. GRANT ROLLNAME TO ADMINNAME;  — 사용자에게 만들어진 롤 부여
        - 권한 부여 시 재접속을 해야 권한이 적용된다.
- 제약조건
    
    <aside>
    💡 제약조건에는 PRIMARY KEY, NOT NULL, UNIQUE, CHECK, FOREIGN KEY의 5가지 방법이 있다.
    
    </aside>
    
    - PRIMARY KEY(기본키)
        - 테이블에서 한 행의 정보를 찾기 위해 사용 할 컬럼. 테이블에 대한 식별자 역할을 한다.
        - NOT NULL + UNIQUE의 제약조건을 가지고 있다.
        - 테이블 당 한 개의 PRIMARY KEY만 설정 가능하다.
        - 컬럼 레벨, 테이블 레벨 둘 다 설정 가능하다.
        - 한 개의 컬럼에만 사용(단일키)할 수 있고, 여러 개의 컬럼을 묶어서 설정(COMPOSITE KEY, 복합키)할 수도 있다.
        
        ```sql
        USER_NO NUMBER CONSTRAINT PK_USER_NO PRIMARY KEY
        -- 단일키
        CONSTRAINT PK_USER_NO_USER_ID PRIMARY KEY(USER_NO, USER_ID)
        -- 복합키
        ```
        
    - FOREIGN KEY(외부키/외래키)
        - 참조된 다른 테이블에서 제공하는 값만 사용할 수 있다.
        - 참조 무결성을 위배하지 않기 위해 사용한다.
        - FOREIGN KEY 제약조건에 의해 테이블 간의 관계가 형성된다.
        - FOREIGN KEY에서 제공되는 값과 NULL 외에는 사용할 수 없다.
        
        ```sql
        CONSTRAINT FK_GRADE_CODE FOREIGN KEY (GRADE_CODE) REFERENCES USER_GRADE (GRADE_CODE)
        ```
        
        - 외부키는 삭제할 수 있다. 그 방법은 아래와 같다. 단, 삭제룰을 적용하지 않은 상태에서, 이미 참조하고 있는 외래키는 삭제가 불가능하다.
            
            ```sql
            DELETE FROM USER_GRADE WHERE GRADE_CODE = 10;
            ```
            
        - 삭제룰은 두 가지 방법이 있다. 삭제 시 값을 NULL로 지정하는 방법과, 삭제시 참조한 컬럼도 같이 삭제하는 방법. 각각의 삭제룰은 아래와 같다.
            
            ```sql
            ON DELETE SET NULL
            ON DELETE CASCADE
            ```
            
    - NOT NULL
        - NOT NULL의 경우에는 해당 컬럼에 NULL값을 허용하지 않을 때 사용하며, 컬럼 레벨에서만 제한이 가능하다.
            
            ```sql
            CREATE TABLE USER_NOTNULL(
              USER_NO NUMBER NOT NULL,              -- NOT NULL은 반드시 컬럼 레벨에서 제약조건 설정을 해야 한다.
              USER_ID VARCHAR2(20) NOT NULL,
              EMAIL VARCHAR2(50)
            );
            ```
            
    - UNIQUE
        - UNIQUE의 경우에는 컬럼에 입력되는 값에 대해 중복을 제한할 때 사용한다. 컬럼 레벨과 테이블 레벨에서 설정 가능하다.
            
            ```sql
            CREATE TABLE USER_UNIQUE2(
              USER_NO NUMBER UNIQUE,
              USER_ID VARCHAR2(20),
              USER_PWD VARCHAR2(30),
              USER_NAME VARCHAR2(30),
              GENDER VARCHAR2(10),
              PHONE VARCHAR2(30),
              EMAIL VARCHAR2(50),
              UNIQUE(USER_ID, USER_PWD)
            --  UNIQUE(USER_ID)
            --  UNIQUE(USER_FWD)
            );
            ```
            
            - 단, 위와 아래의 주석으로 처리된 UNIQUE제약은 다르다. UNIQUE(USER_ID, USER_PWD)의 경우, ID값과 PWD값이 일치하는 경우가 중복될 때 제약이 걸리며, 
            아래의 경우 ID값과 PWD값이 별개로, 각자 중복될 때마다 제약이 걸린다.
    - CHECK
        - CHECK의 경우에는 컬럼에 기록되는 값에 특정 조건을 설정할 수 있다. 컬럼 레벨과 테이블 레벨에서 설정 가능하며, 
        CHECK(컬럼명 비교연산자 비교값)의 형식을 따른다.
            
            ```sql
            CREATE TABLE USER_CHECK(
              USER_NO NUMBER,
              USER_ID VARCHAR2(20) UNIQUE,
              USER_PWD VARCHAR2(30) NOT NULL,
              USER_NAME VARCHAR2(30),
              GENDER VARCHAR2(10) CHECK(GENDER IN ('남','여')),
              CHECK(USER_NAME IN ('김','수'))
            );
            ```
            
    - CONSTRAINT
        - CONSTRAINT 를 통해 제약의 이름을 정할 수 있다.
            
            ```sql
            USER_NO NUMBER CONSTRAINT UK_USER_NO NOT NULL UNIQUE,
            ```
            
    - 제약조건 확인을 위한 딕셔너리 뷰
        - 아래 SELECT문을 이용하여 제약조건들을 확인 가능하다.
            
            ```sql
            SELECT 
                   A.TABLE_NAME
                 , B.CONSTRAINT_NAME
                 , A.SEARCH_CONDITION
                 , A.CONSTRAINT_TYPE
                 , B.COLUMN_NAME
              FROM USER_CONSTRAINTS A
              JOIN USER_CONS_COLUMNS B ON(A.CONSTRAINT_NAME = B.CONSTRAINT_NAME)
             WHERE A.TABLE_NAME = 'TABLENAME';
            ```
            
    - 제약조건 삭제(정지)
        - 제약조건의 위배로 인해(부모키) 삭제가 불가능 할 때는, 제약조건도 같이 삭제가 필요하다.
            
            ```sql
            ALTER TABLE TABLENAME
            DROP COLUMN COLUMNNAME CASCADE CONSTRAINTS;
            ```
            
        - 제약조건 삭제에는 DROP 명령어를 사용한다.
            
            ```sql
            ALTER TABLE TABLENAME
            DROP CONSTRAINT 제약조건이름;
            ```
            
        - 혹은, 제약조건을 잠시 종료 후 다시 작동할 수 있다.
            
            ```sql
            ALTER TABLE TABLE_NAME
            DISABLE CONSTRAINT 제약조건이름;
            -- 제약조건 종료
            
            ALTER TABLE TABLE_NAME
            ENABLE CONSTRAINT 제약조건이름;
            -- 제약조건 재 작동
            ```
            

# 5. VIEW

- VIEW의 정의
    - SELECT 쿼리문을 저장한 객체로 가상테이블이라고 불린다.
    - 실질적인 데이터를 물리적으로 저장하고 있지 않다.
    - 테이블을 사용하는 것과 동일하게 사용할 수 있다.
    - 보안과 편의성을 위해 사용한다.
    - 테이블의 작성은 개발자의 영역이 아니고, 개발자가 직접 테이블을 작업하는 것은 DB의 무결성과 효율을 낮출 수 있기에 개발자는 뷰의 형태로 받아들이게 된다.
        
        ```sql
        CREATE OR REPLACE VIEW V_RESULT_EMP
        AS
        SELECT 
               EMP_ID
             , EMP_NAME
             , JOB_CODE
             , DEPT_CODE
          FROM EMPLOYEE;
          
        SELECT 
              * 
          FROM V_RESULT_EMP;
        ```
        
    - 위와 같은 형태로 사용되며, 이중쿼리문의 형태를 따른다.
    - UPDATE문을 통해 베이스 테이블의 정보가 변경되면 VIEW의 결과도 같이 변경된다.
        
        <aside>
        💡 SELECT * FROM EMPLOYEE;
        UPDATE
        EMPLOYEE
        SET EMP_NAME = '정중앙'
        WHERE EMP_ID = '205';
        
        </aside>
        
- Data Dictionary View
    - 자원을 효율적으로 관리하기 위해 다양한 정보를 저장하는 시스템 테이블(Data Dictionary).
    - 사용자가 테이블을 생성하거나, 사용자를 변경하는 등의 작업을 할 때 데이터베이스 서버에 의해 자동으로 갱신되는 테이블(Data Dictionary)
    - 원본 테이블을 커스터마이징 해서 보여주는 베이스 테이블(Data Dictionary View)
    - DBA_XXX: 데이터베이스 관리자만 접근이 가능한 객체들의 정보 조회
    ALL_XXX: 자신의 계정 + 권한을 부여받은 객체의 정보 조회
    USER_XXX: 자신의 계정이 소유한 객체 등에 대한 정보 조회
    - 아래와 같은 방식으로 계정이 가진 테이블과 컬럼을 확인할 수 있다.
        
        ```sql
        SELECT
               * 
          FROM USER_TAB_COLUMNS
         WHERE TABLE_NAME = 'TABLENAME';
        ```
        
- VIEW 활용
    - VIEW 서브쿼리 안에 연산 결과의 컬럼도 포함할 수 있다.
        
        ```sql
        CREATE OR REPLACE VIEW V_EMP_JOB
        (
          사번
        , 이름
        , 직급명
        , 성별
        , 근무년수
        )
        AS
        SELECT A.EMP_ID
             , A.EMP_NAME
             , B.JOB_NAME
             , DECODE(SUBSTR(A.EMP_NO, 8, 1), '1', '남', '여')
             , EXTRACT(YEAR FROM SYSDATE) - EXTRACT(YEAR FROM A.HIRE_DATE)
          FROM EMPLOYEE A
          JOIN JOB B ON (A.JOB_CODE = B.JOB_CODE);
        ```
        
    - VIEW에 DML 명령어도 몇몇 경우에는 가능하다. (단, 어지간하면 하지말자. 그냥 TABLE에 하자.)
        
        ```sql
        INSERT 
          INTO V_JOB
        VALUES
        (
          'J8'
        , '인턴'  
        );
        -- INSERT
        
        UPDATE
               V_JOB A 
           SET A.JOB_NAME = '알바'
         WHERE A.JOB_CODE = 'J8';
        -- UPDATE
        
        DELETE
          FROM V_JOB A
         WHERE A.JOB_CODE = 'J8';
        -- DELETE
        ```
        
    - VIEW에 DML 명령어를 사용할 수 없는 경우: 
    1. 뷰 정의에 포함되지 않은 컬럼을 조작하는 경우
    2. 뷰에 포함되지 않는 컬럼 중에, 베이스가 되는 테이블 컬럼이 NOT NULL 제약조건이 지정된 경우
    3. 산술표현식으로 정의 된 경우
    4. JOIN을 이용해 여러 테이블을 연결한 경우
    5. DISTINCT를 포함한 경우
    6. 그룹함수나 GROUP BY 절을 포함한 경우
- VIEW 옵션
    - REPLACE: 기존에 동일한 뷰 이름이 존재하는 경우 덮어쓰는 방식으로 생성한다.
    - FORCE: 서브쿼리에 사용 된 테이블이 존재하지 않아도 뷰를 생성한다.
        
        ```sql
        CREATE OR REPLACE FORCE VIEW V_EMP
        AS
        SELECT TOODE
             , TNAME
             , TOONTENTS
          FROM TT;
        ```
        
    - WITH CHECK OPTION: 조건절에 사용된 컬럼의 값을 수정하지 못하게 한다.
        
        ```sql
        CREATE OR REPLACE VIEW V_EMP2
        AS
        SELECT A.*
          FROM EMPLOYEE A
         WHERE MANAGER_ID = '200'
          WITH CHECK OPTION;
        ```
        
    - WITH READ ONLY: DML 수행이 불가능하게 하는 옵션
        
        ```sql
        CREATE OR REPLACE VIEW V_DEPT
        AS
        SELECT A.*
          FROM DEPARTMENT A
          WITH READ ONLY;
        ```
        

# 6. JOIN

<aside>
💡 JOIN: 한 개 이상의 테이블을 합쳐서 하나의 결과로 조회하기 위해 사용한다. 기본값은 INNER JOIN & EQUAL JOIN이다.

</aside>

- INNER JOIN
    - 맵핑이 되지 않으면 출력을 하지 않는다. (NULL값은 무시한다.)
    - 오라클 전용 구문은 아래와 같은 방식으로 사용한다. (연결에 사용 할 두 컬럼의 컬럼명이 다른 경우)
        
        ```sql
        SELECT
               EMP_ID
             , EMP_NAME
             , DEPT_CODE
             , DEPT_ID
             , DEPT_TITLE
             , LOCATION_ID
          FROM EMPLOYEE
             , DEPARTMENT
         WHERE DEPT_CODE = DEPT_ID;
        ```
        
    - 열결에 사용할 두 컬럼의 컬럼명이 같을 경우, 참조값을 알기 위해 참조연산자 활용이 필요하다. 이 때, 별칭을 사용하는 편이 권장된다. 방법은 아래와 같다.
        
        ```sql
        SELECT
               A.EMP_ID
             , A.EMP_NAME
             , A.JOB_CODE
             , B.JOB_NAME
          FROM EMPLOYEE A
             , JOB B
         WHERE A.JOB_CODE = B.JOB_CODE;
        ```
        
    - ANSI의 경우, 문법의 차이가 있을 뿐 결과는 동일하다. ON()과 USING()을 사용한다. 단, USING()의 경우 별칭을 사용할 수 없어 권장되진 않는다. 방법은 아래와 같다.
        
        ```sql
        SELECT
               A.EMP_ID
             , A.EMP_NAME
             , A.JOB_CODE     
             , B.JOB_CODE
          FROM EMPLOYEE A
          JOIN JOB B ON (A.JOB_CODE = B.JOB_CODE);
        
        SELECT
               EMP_ID
             , EMP_NAME
             , JOB_CODE     
             , JOB_CODE
          FROM EMPLOYEE 
          JOIN JOB USING (JOB_CODE);
        ```
        
- OUTER JOIN
    - 맵핑이 되지 않아도 기준값에 따라 출력이 가능하다.
    - 두 테이블의 지정하는 컬럼 값이 일치하지 않는 행도 조인에 포함시킬 수 있다. OUTER JOIN임을 명시해야 한다. (LEFT, RIGHT, FULL의 3종류가 있으며, OUTER는 생략 가능하나 방향은 명시해야 한다.)
    - LEFT OUTER JOIN: 합치기에 사용한 두 테이블 중 왼편에 기술 된 테이블의 행의 수를 기준으로 JOIN한다.
        
        ```sql
        SELECT
               A.EMP_NAME
             , B.DEPT_TITLE
          FROM EMPLOYEE A
        --  LEFT OUTER JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID);
          LEFT JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID);
        -- ANSI 표준
        
        SELECT
               A.EMP_NAME
             , B.DEPT_TITLE 
          FROM EMPLOYEE A
             , DEPARTMENT B
         WHERE A.DEPT_CODE = B.DEPT_ID(+);
        -- ORACLE 전용
        ```
        
    - RIGHT OUTER JOIN: 합치기에 사용된 두 테이블 중 오른편에 기술 된 테이블의 행의 수를 기준으로 JOIN한다.
        
        ```sql
        SELECT
               A.EMP_NAME
             , B.DEPT_TITLE
          FROM EMPLOYEE A
        --  RIGHT OUTER JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID);
          RIGHT JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID);
        -- ANSI 표준
        
        SELECT 
               A.EMP_NAME
             , B.DEPT_TITLE
          FROM EMPLOYEE A
             , DEPARTMENT B
         WHERE A.DEPT_CODE(+) = B.DEPT_ID;
        -- ORACLE 전용
        ```
        
    - FULL OUTER JOIN: 합치기에 사용한 두 테이블이 가진 모든 행을 결과에 포함하여 JOIN한다. 단, ORACLE표준의 (+)는 지원되지 않아, ANSI 표준으로 사용한다.
        
        ```sql
        SELECT 
               A.EMP_NAME
             , B.DEPT_TITLE
          FROM EMPLOYEE A
        --  FULL OUTER JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID);
          FULL JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID);
        ```
        
    - CROSS JOIN: 카테이션곱이라고도 한다. 조인되는 테이블들의 각 행동이 모두 매핑되게 데이터를 검색하는 방법이다. 그러나 보통 사용하는 경우는 없고, RIGHT, LEFT등의 기준을 정하지 않거나 오류 상황 시 주로 발생한다.
        
        ```sql
        SELECT 
               A.EMP_NAME
             , B.DEPT_TITLE
          FROM EMPLOYEE A             
         CROSS JOIN DEPARTMENT B;
        ```
        
- NON EQUAL JOIN(NON EQU JOIN)
    - 매핑하는 두 컬럼 값이 = 관계가 성립되지 않을 때 사용한다. 단, DB단게에서는 서로 다른 테이블의 JOIN관계가 필요할 시 외래키를 이용 =관계가 성립되도록 지정하므로, 자주 사용되는 방식은 아니다. 비교연산자를 사용한다.
        
        ```sql
        SELECT
               A.EMP_NAME
             , A.SALARY
             , B.SAL_LEVEL
             , B.MIN_SAL
             , B.MAX_SAL
          FROM EMPLOYEE A
        --  JOIN SAL_GRADE B ON(A.SALARY BETWEEN B.MIN_SAL AND B.MAX_SAL);
          JOIN SAL_GRADE B ON(A.SALARY >= B.MIN_SAL AND A.SALARY <= B.MAX_SAL);
        -- ANSI 표준
        
        SELECT
               A.EMP_NAME
             , A.SALARY
             , B.SAL_LEVEL
             , B.MIN_SAL
             , B.MAX_SAL 
          FROM EMPLOYEE A
             , SAL_GRADE B
        -- WHERE A.SALARY BETWEEN B.MIN_SAL AND B.MAX_SAL;
         WHERE A.SALARY >= B.MIN_SAL AND A.SALARY <= B.MAX_SAL;
        -- ORACLE 전용
        ```
        
- SELF JOIN
    - 같은 테이블을 조인하는 경우, 자기 자신인 테이블과 조인을 맺는 것.
        
        ```sql
        SELECT
               A.EMP_ID
             , A.EMP_NAME 사원이름
             , A.DEPT_CODE
             , A.MANAGER_ID
             , B.EMP_NAME 관리자이름
          FROM EMPLOYEE A
          LEFT JOIN EMPLOYEE B ON (A.MANAGER_ID = B.EMP_ID);
        -- ANSI 표준
        
        SELECT
               A.EMP_ID
             , A.EMP_NAME 사원이름
             , A.DEPT_CODE
             , A.MANAGER_ID 
             , B.EMP_NAME 관리자이름
          FROM EMPLOYEE A
             , EMPLOYEE B 
         WHERE A.MANAGER_ID = B.EMP_ID(+);
        -- 오라클 전용
        ```
        
    - 위 코드의 경우, EMPLOYEE 테이블 안에 있는 MANAGER_ID를 참조, EMP_ID를 참조한 MANAGER_ID가 존재하는 (NOT NULL)행만 불러온다.
        
        ![1.png](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/1%202.png)
        
- 다중 JOIN
    - N개(3개 이상)의 테이블을 조회할 때 사용한다. 문법은 일반적인 JOIN문과 다르지 않으나, 각 테이블 간의 관계성 파악이 필요하다.
        
        ```sql
        SELECT 
               A.EMP_ID 사번
             , A.EMP_NAME  이름
             , B.JOB_NAME 직급명
             , C.DEPT_TITLE 부서명
             , D.LOCAL_NAME 근무지역명
             , A.SALARY 급여
          FROM EMPLOYEE A  
          LEFT JOIN JOB B ON(A.JOB_CODE = B.JOB_CODE)
          LEFT JOIN DEPARTMENT C ON(A.DEPT_CODE = C.DEPT_ID)
          LEFT JOIN LOCATION D ON(C.LOCATION_ID = D.LOCAL_CODE)
         WHERE B.JOB_CODE = 'J6' AND D.LOCAL_NAME LIKE 'ASIA%';
        ```
        
    - 위 코드의 경우, 참조하는 컬럼에 따라 A-B, A-C, C-D의 관계를 가진다. 각 테이블을 연결하여 하나의 거대한 테이블을 만든다고 생각하자.

# 7. SUBQUERY

- SUBQUERY 정의
    
    ![Untitled](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/Untitled%206.png)
    
    - 하나의 SELECT 문장의 절 안에 포함된 또 하나의 SELECT 문장이다. 서브쿼리는 메인쿼리가 실행되기 이전에 한번만 실행되며, 비교연산자의 오른쪽에 기술해야 하며, 반드시 괄호()로 묶어야 한다.
    - 또한 서브쿼리와 비교할 항목은 반드시 서브쿼리의 SELECT한 항목의 개수와 자료형을 일치시켜야 한다.
    - 서브쿼리의 사용 위치는
        
        SELECT 절, FROM 절, WHERE절, GROUP BY 절, HAVING 절, ORDER BY 절
        DML 구문: INSERT문, UPDATE문, DELETE문
        DDL 구문: CREATE TABLE문, CREATE VIEW문
        
        와 같다.
        
    - 특히, FROM절에서 사용한 서브쿼리는 테이블 대신 사용하며, 인라인 뷰(INLINE VIEW)라고도 한다.
- 단일행 서브쿼리
    - 서브쿼리의 조회 결과 값의 개수가 1개일 때 사용한다.
    - 서브쿼리의 유형에 따라 서브쿼리 앞에 붙는 연산자가 다르다.
    단일행 서브쿼리 앞에는 일반 비교 연산자
    >, <, >=, <=, =, !=/<>/^=
    가 붙는다.
        
        ```sql
        SELECT 
               EMP_NO
             , EMP_NAME
             , DEPT_CODE
             , SALARY
          FROM EMPLOYEE
         WHERE SALARY >= (SELECT AVG(SALARY) 
                            FROM EMPLOYEE
                          );
        ```
        
    - 단일행 서브쿼리는 위와 같은 방식으로 사용한다.
- 다중행 서브쿼리
    - 서브쿼리의 조회 결과 값이 행이 여러 개일 때 사용한다.
    - 다중행 서브쿼리 앞에서는 일반 연산자 사용이 불가능하다.
        
        > IN / NOT IN: 여러 개의 결과 값 중에서 한 개라도 일치하는 값이 있다면
        혹은 없다면이라는 의미
        >ANY / <ANY: 여러 개의 결과 값 중에서 한 개라도 큰 / 작은 경우
        가장 작은 값보다 큰지 / 가장 큰 값보다 작은지
        (서브쿼리의 결과들 중에 어떤 것보다도 크거나 작기만 하면 된다.)
        >ALL / <ALL: 모든 값보다 크거나 / 작은 경우
        가장 큰 값보다 큰가 / 가장 작은 값보다 작은가
        (모든 서브쿼리의 결과들보다 크거나 작아야 한다.)
        EXISTS / NOT EXISTS: 서브쿼리에만 사용하는 연산자로
        값이 존재하는가 / 존재하지 않는가
        > 
    
    ```sql
    -- IN/NOT IN
    SELECT
           A.EMP_NO 사번
         , A.EMP_NAME 이름
         , B.DEPT_TITLE 부서명
         , C.JOB_NAME 직급명
         , '관리자' AS 구분
      FROM EMPLOYEE A
      LEFT JOIN DEPARTMENT B ON(A.DEPT_CODE = B.DEPT_ID)
      LEFT JOIN JOB C ON (A.JOB_CODE = C.JOB_CODE)
     WHERE A.EMP_ID IN (SELECT DISTINCT MANAGER_ID 
                          FROM EMPLOYEE
                         WHERE MANAGER_ID IS NOT NULL
                        );
    ```
    
    - EMPLOYEE, DEPARTMENT, JOB의 테이블 3개를 JOIN(DEPARTMENT 값이 NULL인 값이 존재하기에, LEFT JOIN).
    - SUBQUERY로 MANAGER_ID IS NOT NULL 구한 뒤, 단일행이 아니기에 IN 연산자를 사용하여 값 비교. 출력결과는 MANAGER ID가 있는 직원들의 MANAGER_ID(=EMP_ID). 즉, 매니저 직급인 직원들의 정보 SELECT.
    
    ```sql
    > ALL, < ALL
    SELECT
           A.EMP_NO
         , A.EMP_NAME
         , B.JOB_NAME
         , A.SALARY
      FROM EMPLOYEE A
      JOIN JOB B ON(A.JOB_CODE = B.JOB_CODE)
     WHERE A.SALARY > ALL (SELECT C.SALARY
                             FROM EMPLOYEE C
                             JOIN JOB D ON (C.JOB_CODE = D.JOB_CODE)
                            WHERE JOB_NAME = '차장'
                           )
       AND B.JOB_NAME = '과장';
    ```
    
    - 이중쿼리의 차장의 모든 SALARY보다 SALARY가 많은 과장이 있다면, 정보를 SELECT하는 구문.
    
    ```sql
    > ANY, < ANY
    SELECT
           A.EMP_NO
         , A.EMP_NAME
         , B.JOB_NAME
         , A.SALARY
      FROM EMPLOYEE A
      JOIN JOB B ON (A.JOB_CODE = B.JOB_CODE)
     WHERE B.JOB_NAME = '대리'
       AND A.SALARY > ANY (SELECT C.SALARY 
                             FROM EMPLOYEE C
                             JOIN JOB D ON(C.JOB_CODE = D.JOB_CODE)
                            WHERE D.JOB_NAME = '과장' 
                           );
    ```
    
    - 이중 쿼리의 C.SALARY(과장의 월급) 보다 많은 월급을 받는 대리(A.SALARY)가 있다면 그 정보를 출력 하는 구문.
    
    ```sql
    EXISTS / NOT EXISTS 
    SELECT 
           A.EMP_NAME 
      FROM EMPLOYEE A
     WHERE NOT EXISTS (SELECT B.EMP_NAME 
                         FROM EMPLOYEE B
                        WHERE B.EMP_ID = '100'
                      );
    ```
    
    - 이중쿼리의 B.EMP_ID =’100’에 해당하는 EMP_ID가 있는지 없는지 확인하는 구문.  메인 쿼리의 정보와 비교하는 것이 아니라, 서브쿼리 안에 해당하는 정보가 있는지 없는지만을 확인한다.
- 다중열 서브쿼리
    - 서브쿼리의 조회 결과 컬럼의 개수가 여러 개 일 때 사용한다.
        
        ```sql
        SELECT
               A.EMP_NAME 이름
             , B.JOB_NAME 직급
             , C.DEPT_TITLE 부서
             , A.HIRE_DATE 입사일
          FROM EMPLOYEE A
          JOIN JOB B ON (A.JOB_CODE = B.JOB_CODE)
          JOIN DEPARTMENT C ON (A.DEPT_CODE = C.DEPT_ID)
         WHERE (A.DEPT_CODE, A.JOB_CODE) IN (SELECT D.DEPT_CODE
                                                  , D.JOB_CODE  
                                               FROM EMPLOYEE D
                                              WHERE D.ENT_YN = 'Y' 
                                                AND SUBSTR(D.EMP_NO,8,1) = '2'
                                             )
        -- AND A.ENT_YN = 'N';
        ```
        
- 다중행 다중열 서브쿼리
    - 서브쿼리의 조회 결과 컬럼의 개수와 행의 개수가 여러 개 일 때  사용한다.
    
    ```sql
    SELECT 
           A.EMP_NO
         , A.EMP_NAME
         , A.JOB_CODE
         , A.SALARY
      FROM EMPLOYEE A
     WHERE (A.JOB_CODE,A.SALARY) IN (SELECT B.JOB_CODE
                                          , TRUNC(AVG(B.SALARY),-4)
                                       FROM EMPLOYEE B
                                      GROUP BY B.JOB_CODE
                                     );
    ```
    
    - 서브쿼리의 B.JOB_CODE와 TRUNC(AVG(B.SALARY),-4)가 메인쿼리의 A.JOB_CODE와 A.SALARY와 일치하는 값이 있는지(IN)를 확인하는 구문.
    - A.JOB_CODE와 B.JOB_CODE를 비교하고, A.SALARY와 TRUNC(AVG(B.SALARY), -4)를 비교한다.
- INLINE VIEW
    
    ```sql
    SELECT
           B.JOB_CODE
         , B.JOBAVG
         , C.EMP_NAME
         , C.SALARY
         , D.JOB_NAME
      FROM (SELECT A.JOB_CODE
                 , TRUNC(AVG(A.SALARY), -4) AS JOBAVG
              FROM EMPLOYEE A
             GROUP BY A.JOB_CODE
            ) B
      JOIN EMPLOYEE C ON (B.JOB_CODE = C.JOB_CODE)
      JOIN JOB D ON (C.JOB_CODE = D.JOB_CODE);
    ```
    
    - 테이블 대신 사용하며, 서브쿼리가 만들 결과 집합(RESULT SET)으로부터 시작한다.
    - 위와 같은 방식으로 사용한다. 단, 서브쿼리의 컬럼에 별칭을 달 경우, 메인쿼리에서는 반드시 별칭을 붙여 식별해야 한다. 만약 별칭이 없다면, 연산식이 아닌 컬럼의 경우에는 메인쿼리에서 컬러명으로 조회가 가능하다.
    - 단, 인라인 뷰의 서브쿼리에 연산식으로 도출된 컬럼이 있다면, 그 때는 인라인 뷰에서 반드시 별칭을 달고, 메인 쿼리에서도 해당 별칭으로만 조회가 가능하다.
        
        ```sql
        SELECT
               D.이름
             , D.부서명
             , D.직급명
          FROM (SELECT A.EMP_NAME 이름
                     , B.DEPT_TITLE 부서명
                     , C.JOB_NAME 직급명
                  FROM EMPLOYEE A
                  JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID)
                  JOIN JOB C ON (A.JOB_CODE = C.JOB_CODE)
                ) D
         WHERE D.부서명 = '총무부';
        ```
        
    - 인라인 뷰를 활용하여 TOP-N 분석이 가능하다.
    - ROWNUM (행 번호, 순번)을 활용하며, FROM의 결과 행(튜플)에 자동으로 순번이 달리게 할 수 있다.
    - 단, ROWNUM을 활용한 조건절에서는 반드시 1순위부터 포함되게 범위를 지정해야 한다. 만일 다른 순위부터 하고 싶다면, 3중쿼리를 이용해야 한다.
        
        ```sql
        SELECT
               C.DEPT_CODE 
             , C.DEPT_TITLE
             , C.급여평균
          FROM (SELECT A.DEPT_CODE
                     , B.DEPT_TITLE
                     , TRUNC(AVG(SALARY),-4) 급여평균
                  FROM EMPLOYEE A
                  JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID)
                 GROUP BY A.DEPT_CODE, B.DEPT_TITLE
                 ORDER BY 3 DESC
                ) C
         WHERE ROWNUM <=3;
        ```
        
    - JOIN, GROUP BY, ROWNUM, INLINE VIEW, ORDER BY를 활용한 부서별 급여 평균 상위 3위 부서의 부서 코드, 부서명, 평균 급여 조회 코드.
    - INLINE VIEW의 DEPT_CODE, DEPT_TITLE, 급여평균을 ORDER BY로 정렬한 뒤, 정렬한 값들을 기준으로 SELECT 후 WHERE문을 활용, 3위 안쪽의 정보만을 출력한다.
- WITH AS
    - WITH 이름 AS (서브쿼리문)의 형식을 가진다.
    - 서브쿼리에 이름을 붙여주고 메인쿼리에서 사용 시 붙여진 이름을 사용할 수 있다.
    - 중복을 줄이고, 속도가 빨라지며, 가독성이 좋아진다.
        
        ```sql
        WITH
              TOPN_SAL   
           AS (SELECT A.EMP_ID
                    , A.EMP_NAME
                    , A.SALARY
                 FROM EMPLOYEE A
                ORDER BY A.SALARY DESC
               )
        SELECT
               ROWNUM
             , B.EMP_NAME
             , B.SALARY
          FROM TOPN_SAL B
         WHERE ROWNUM <= 3
        ```
        
    - SELECT ~ DESC의 서브쿼리문에 TOPN_SAL 이라는 이름을 붙인 뒤, SELECT ~ ROWNUM <+ 3;의 메인쿼리에서 해당 이름을 TABLE처럼 사용할 수 있다.
    - 다수의 서브쿼리를 별개의 이름을 붙여줌으로서 사용 역시 가능하다.
- 상(호연)관 서브쿼리
    - 메인쿼리가 사용하는 테이블의 값을 서브쿼리가 이용해서 결과를 만들 수 있다.
    - 메인쿼리의 테이블 값이 변경되면 서브쿼리의 결과값도 변경된다.
    - 상관 서브쿼리는 EXISTS 연산자만 사용 가능하다.
        
        ```sql
        SELECT
               A.EMP_ID
             , A.EMP_NAME
             , A.DEPT_CODE
             , A.MANAGER_ID
          FROM EMPLOYEE A
         WHERE EXISTS (SELECT B.EMP_ID
                         FROM EMPLOYEE B
                        WHERE A.MANAGER_ID = B.EMP_ID  
                        );
        ```
        
    - 메인쿼리에 있는 EMPLOYEE A 를 서브쿼리에서 사용할 수 있다.
- 스칼라 서브쿼리
    - 상관 서브쿼리의 값이 단일행으로 출력될 때 스칼라 서브쿼리라고 한다.
        
        ```sql
        SELECT
               A.EMP_ID
             , A.EMP_NAME
             , A.MANAGER_ID
             , NVL((SELECT B.EMP_NAME
                      FROM EMPLOYEE B
                     WHERE A.MANAGER_ID = B.EMP_ID
                    ), '없음') 
          FROM EMPLOYEE A;
        ```
        
    - 서브쿼리의 B.EMP_NAME이 단일행인 상관서브쿼리기에, 스칼라 서브쿼리.
    - 스칼라 서브쿼리 역시 SELECT 절 뿐만 아니라 ORDER BY, WHERE 절 등 다양한 곳에서 사용 가능하다.
        
        ```sql
        SELECT 
               A.EMP_ID
             , A.EMP_NAME
             , A.DEPT_CODE
          FROM EMPLOYEE A
         ORDER BY (SELECT B.DEPT_TITLE
                     FROM DEPARTMENT B
                    WHERE A.DEPT_CODE = B.DEPT_ID 
                   ) DESC NULLS LAST;
        ```
        
    - ORDER BY 절에 스칼라 서브쿼리를 응용하여 JOIN을 사용하지 않고 ORDER BY 하는 코드.

# 8. SEQUENCE

- SEQUENCE 정의
    - 순차적으로 정수 값을 자동으로 생성하는 객체.
        
        ![4.png](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/4.png)
        
- SEQUENCE 생성과 활용
    - 위는 INCREMENT를 한 번 돌리는 구문, 아래는 현재 시퀸스의 값을 구하는 구문. 
    → 생성 후 바로 CURRVAL을 사용할 수 없다. NEXTVAL로 값 부여 후 사용 가능.
        
        ```sql
        SELECT SEQ_EMPID.NEXTVAL FROM DUAL;
        SELECT SEQ_EMPID.CURRVAL FROM DUAL;
        ```
        
    - SEQ_EMPID리는 이름의 SEQUENCE 생성. 
    해당 객체는 300으로부터 시작하여 5식 증가하며, 310의 최대치를 가지고, 300의 최소치를 가지며, MAXVALUE에 도달 시 CYCLE이 진행되어 MINVALUE로 돌아가며, CACHE를 저장하지 않는다.
        
        ```sql
        CREATE SEQUENCE SEQ_EMPID
         START WITH 300
        INCREMENT BY 5
        MAXVALUE 310
        MINVALUE 300
        CYCLE
        NOCACHE;
        ```
        
    - 시퀸스 역시 ALTER를 이용해 옵션 변경이 가능하다.
        
        → 위 구문의 INCREMENT를 5→10으로, MAXVALUE를 310 → 400으로, CYCLE → NOCYCLE로 변경한 것. 
        → 단, START WITH 옵션은 변경 불가능하다.
        
        ```sql
        ALTER SEQUENCE SEQ_EMPID
        INCREMENT BY 10
        MAXVALUE 400
        NOCYCLE;
        ```
        
- 참고사항
    - SELECT문에서 사용 가능
    INSERT문에서 SELECT 구문 사용 가능 (서브쿼리)
    INSERT문에서 VALUES 절에서 사용 가능
    UPDATE문에서 SET 절로 사용 가능
    - 단, 일반적인 서브쿼리의 SELECT 문에서는 사용 불가 
    VIEW의 SELECT 절에서 사용 불가
    DISTINCT 키워드가 있는 SELECT 문에서는 사용 불가
    GROUP BY, HAVING 절에 있는 SELECT 문에서는 사용 불가
    ORDER BY 절에서 사용 불가
    CREATE TABLE, ALTER TABLE에 DEFAULT값으로 사용 불가

# 9. INDEX

- INDEX 정의
    
    ![5.png](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/5.png)
    
    - SQL 명령문의 처리 속도를 향상시키기 위해서 컬럼에 대해서 생성하는 오라클 객체.
    내부구조는 B*트리 형식으로 구성되어 있다.
    - 하드 디스크의 어느 위치에 정보가 있는지에 대한 정보를 가진 주소록
    DATA - ROWID로 구성
    - 장점: 검색속도가 빨라지고 시스템에 걸리는 부하를 줄여 전체 성능을 향상시킬 수 있다.
    - 단점: 인덱스를 위한 추가 저장 공간이 필요하고, 생성하는데 시간이 필요하다. 따라서 데이터의 변경 작업(INSERT/UPDATE/DELETE)이 자주 일어날 경우에는 REBUILD 작업이 추가적으로 필요하며, REBUILD 작업이 이루어지지 않을 시  오히려 성능이 저하된다.  
    → 따라서 일반적으로 테이블 전체 로우 수의 15% 이하의 데이터를 조회할 때 인덱스를 활용한다.
        
        ```sql
        ALTER INDEX INDEXNAME REBUILD;
        -- INDEX REBUILD
        ```
        
- INDEX 활용
    - 인덱스를 관리하는 데이터 딕셔너리 뷰
        
        ```sql
        SELECT
               A.* 
          FROM USER_IND_COLUMNS A;
        ```
        
    - ROWID 조회(오브젝트 번호 + 상대 파일 번호 + 블록 번호 + 데이터 번호)
        
        ```sql
        SELECT
               ROWID
             , A.EMP_ID
             , A.EMP_NAME
          FROM EMPLOYEE A;
        ```
        
    - UNIQUE INDEX
    UNIQUE INDEX로 생성 된 컬럼에는 중복 값이 포함될 수 없다.
    오라클 PRIMARY KEY 제약조건을 생성하면 자동으로 해당 컬럼에 UNIQUE INDEX가 생성된다.
    PRIMARY KEY를 이용하여 ACCESS한 경우에는 성능 향상의 효과가 있다.
    - 인덱스 힌트
    일반적으로 옵티마이저가 적절한 인덱스를 타거나(사용하거나) 풀 스캐닝을 해서 비용이 적게 드는 효율적인 방식으로 검색이 진행된다.
    이 때 원하는 테이블에 있는 인덱스를 사용할 수 있도록 해주는 구문(=힌트)를 통해 옵티마이저가 시행할 인덱스를 직접 지정할 수 있다. 
    그 구문은 아래와 같다.
        
        ```sql
        /*+ INDEX DESC(TABLENAME INDEXNAME) */
        ```
        
        - 인덱스는 내림차순으로 생성되기에 DESC를 통해 이전에 넣은 데이터부터 출력되도록 정렬을 해 줘야 한다.
        - 옵티마이저: SQL을 위한 최적의 실행계획을 생성하는 알고리즘
- INDEX MONITORING
    - UNIQUE INDEX를 생성한다.
        
        ```sql
        CREATE UNIQUE INDEX INDEX_NAME
            ON TABLE_NAME(COLUMN_NAME);
        ```
        
    - 해당 인덱스 조회
        
        ```sql
        SELECT 
               * 
          FROM USER_INDEXES
         WHERE TABLE_NAME = 'TABLE_NAME';
        ```
        
    - 모니터링 할 인덱스 설정
        
        ```sql
        ALTER INDEX INDEX_NAME MONITORING USAGE;
        ```
        
    - 인덱스 모니터링 
    아래의 코드를 통해 모니터링이 되고 있는지 확인 가능하다.
        - V$OBJECT_USAGE: 인덱스 활용과 관련된 데이터를 수집하는 뷰
        - USED컬럼: 모니터링 시작 후 해당 인덱스가 사용 되었는지 확인
        
        ```sql
        SELECT INDEX_NAME, TABLE_NAME, MONITORING, USED, START_MONITORING, END_MONITORING
          FROM V$OBJECT_USAGE;
        ```
        
    - 인덱스 모니터링 종료
        
        ```sql
        ALTER INDEX IDX_EID NOMONITORING USAGE;
        ```
        
    - + 결합 인덱스는 카디널리티가 상대적으로 높은(중복도가 낮은) 컬럼을 먼저 써야 한다.
- Cardinality
    
    <aside>
    💡 카디널리티는 중복도가 낮을 수록 높아지며, 중복도가 높을 수록 낮아진다. 
    단, 이는 상대적이다. 카디널리티의 가장 핵심적인 요소는 상대성.
    
    </aside>
    
    - INDEX에서의 카디널리티는 인덱스 내에서 지정된 열에 저장된 값의 고유성을 나타낸다.
    → DISTINCT했을 때의 값.
    - 데이터베이스의 카디널리티는 집합과 집합 사이의 관계성으로도 해석할 수 있다. 1-1 관계, 1-N관계, N-N관계.

# 10. SYNONYM

- SYNONYM 정의
    - 다른 데이터베이스가 가진 객체에 대한 별명 혹은 줄임말.
    - 여러 사용자가 테이블을 공유할 경우, 다른 사용자가 테이블에 접근할 경우 ‘사용자명.테이블명’으로 표현한다.
- SYNONYM 활용
    - 생성 방법은 아래와 같다.
    - 단, SYSTEM 계정에서 권한 부여가 필요하다. (비공개 동의어)
        
        ```sql
        CREATE SYNONYM EMP FOR EMPLOYEE;
        -- EMPLOYEE 테이블을 EMP로 부를 수 있도록 설정
        
        GRANT CREATE SYNONYM TO C##EMPLOYEE;
        -- SYNONYM 권한 부여
        ```
        
    - 특정 테이블 뿐만 아니라, 모든 테이블에서 동의어 사용이 가능하도록 할 수 있다. 단, 이는 SYSTEM 계정에서 가능하다. 
    → 이를 공개 동의어라고 한다.
        
        ```sql
        CREATE PUBLIC SYNONYM DEPT FOR C##EMPLOYEE.DEPARTMENT;
        ```
        

# 11. PL/SQL

- PL/SQL 정의
    - PROCEDURE LANGUAGE EXTNESION TO SQL 또는 PROCEDURE LANGUAGE/SQL
    - 오라클 자체에 내장 된 절차적 언어.
    - SQL의 단점을 보완하여 SQL문장 내에서 변수의 정의, 조건처리, 반복처리, 예외처리 등을 지원한다.
- PL/SQL 활용
    - SET SERVEROUTPUT ON;
    을 통해 출력을 확인할 수 있다.(DEVELOPER 재실행 시 재작동 필요)
    - PL/SQL문의 형식은 아래와 같다.
        
        ```sql
        DECLARE
          EMP_ID EMPLOYEE.EMP_ID%TYPE;
          EMP_NAME EMPLOYEE.EMP_NAME%TYPE;
        BEGIN
          SELECT A.EMP_ID
               , A.EMP_NAME 
            INTO EMP_ID
               , EMP_NAME 
            FROM EMPLOYEE A
           WHERE A.EMP_ID = '&EMP_ID'; 
           
          DBMS_OUTPUT.PUT_LINE('EMP_ID: ' || EMP_ID); 
          DBMS_OUTPUT.PUT_LINE('EMP_NAME: ' || EMP_NAME);
        END;
        /
        ```
        
    - DECLARE ~ BEGIN 절을 통해 변수를 선언한다. 
    BEGIN ~ WHERE 절을 통해 변수의 초기화를 진행한다. 
    DBMS ~ END; / 절을 통해 변수값을 출력한다.
    - 이 때, WHERE절의 ‘&‘ 를 통해 입력창을 출력할 수 있다.
    - %ROWTYPE을 이용하여 테이블의 모든 컬럼과 컬럼의 자료형을 참조할 수 있다.
        
        ```sql
        DECLARE
          EMP EMPLOYEE%ROWTYPE;
        BEGIN
          SELECT A.*  
            INTO EMP    
            FROM EMPLOYEE A
           WHERE A.EMP_ID = '&사번';
        END;
        /
        ```
        
- 조건문
    - 조건문 역시 사용할 수 있다. 
      IF 문:
        
        ```sql
        DECLARE
          SCORE NUMBER;
          GRADE VARCHAR2(3);
        BEGIN
          SCORE := '&점수';
           
          IF SCORE >= 90 THEN GRADE := 'A';
          ELSIF SCORE >= 80 THEN GRADE := 'B';
          ELSIF SCORE >= 70 THEN GRADE := 'C';
          ELSIF SCORE >= 60 THEN GRADE := 'D';
          ELSE GRADE := 'F';
          END IF;
          
          DBMS_OUTPUT.PUT_LINE('당신의 점수는 ' || SCORE
                                || '점이고, 학점은 ' || GRADE
                                || '학점입니다.'); 
        END;
        /
        ```
        
    - CASE 문:
        
        ```sql
        DECLARE
          VEMPNO EMPLOYEE.EMP_ID%TYPE;
          VENAME EMPLOYEE.EMP_NAME%TYPE;
          VDEPTNO EMPLOYEE.DEPT_CODE%TYPE;
          VDNAME VARCHAR2(20) := NULL;
        BEGIN
          SELECT A.EMP_ID
               , A.EMP_NAME
               , A.DEPT_CODE
            INTO VEMPNO
               , VENAME
               , VDEPTNO
            FROM EMPLOYEE A
           WHERE A.EMP_ID = '&사번';
           
          VDNAME := CASE VDEPTNO
                      WHEN 'D1' THEN '인사관리부'   
                      WHEN 'D2' THEN '회계관리부'
                      WHEN 'D3' THEN '마케팅부'
                      WHEN 'D4' THEN '국내영업부'
                      WHEN 'D5' THEN '해외영업1부'
                      WHEN 'D6' THEN '해외영업2부'
                      WHEN 'D7' THEN '해외영업3부'
                      WHEN 'D8' THEN '기술지원부'
                      WHEN 'D9' THEN '총무부'
                      ELSE '부서없음'
                    END;
          
          DBMS_OUTPUT.PUT_LINE ('사번         이름          부서명');
          DBMS_OUTPUT.PUT_LINE ('-------------------------------');
          DBMS_OUTPUT.PUT_LINE (VEMPNO   || '       ' || VENAME || '        '
                                    || VDNAME);
        END;
        /
        ```
        
    - LOOP문 :
        
        ```sql
        DECLARE
          N NUMBER := 1;
        BEGIN
          LOOP
            DBMS_OUTPUT.PUT_LINE(N);
            N := N +1;
            IF N > 5 THEN EXIT;
            END IF;
          END LOOP;
        END;
        /
        ```
        
    - FOR IN문
        
        ```sql
        DECLARE
          RESULT NUMBER;
        BEGIN
          FOR DAN IN 2..9
            LOOP
              IF MOD(DAN , 2) = 0
                THEN FOR SU IN 1..9
                       LOOP
                         RESULT := DAN * SU;
                         DBMS_OUTPUT.PUT_LINE(DAN || ' * ' || SU || ' = ' || RESULT);
                     END LOOP; 
                     DBMS_OUTPUT.PUT_LINE('');
              END IF;
          END LOOP;
        END;
        /
        ```
        
- 테이블 타입의 변수
    - JAVA의 배열과 유사하다.
    - 인덱스 체계가 1번부터 시작한다.
        
        ```sql
        DECLARE
          TYPE EMP_ID_TABLE_TYPE IS TABLE OF EMPLOYEE.EMP_ID%TYPE
          INDEX BY BINARY_INTEGER;
          TYPE EMP_NAME_TABLE_TYPE IS TABLE OF EMPLOYEE.EMP_NAME%TYPE
          INDEX BY BINARY_INTEGER;
          
          EMP_ID_TABLE EMP_ID_TABLE_TYPE;
          EMP_NAME_TABLE EMP_NAME_TABLE_TYPE;  
        
          I BINARY_INTEGER := 1;
        BEGIN
          FOR K IN (SELECT EMP_ID, EMP_NAME FROM EMPLOYEE)
            LOOP
              EMP_ID_TABLE(I) := K.EMP_ID;
              EMP_NAME_TABLE(I) := K.EMP_NAME;
              
              I := I + 1;
          END LOOP;
          
          FOR J  IN 1..(I-1)
            LOOP
              DBMS_OUTPUT.PUT_LINE('EMP_ID: ' || EMP_ID_TABLE(J)
                                    || ', EMP_NAME: ' || EMP_NAME_TABLE(J));
          END LOOP;  
        END;
        /
        ```
        
    - 위 코드를 통해 볼 때. DECLARE ~ BEGIN구문을 통해 변수의 선언과 할당이 이루어진다. 
    TYPE~ INDEX BY BINARY_INTEGER; 구문을 통해 EMP_ID_TABLE_TYPE의 자료형을 정한 뒤, 
    EMP_ID_TABLE라는 변수를 해당 자료형으로 할당(초기화)하는 것.
    - 이후, I BINARY_INTERER := 1; 을 통해 I라는 변수에 자료형과 값을 동시에 할당한다.
    - 이후, BEGIN ~END 절 사이의 FOR문을 통해 할당된 배열에 값을 넣는다.
    - 우선 K 변수에 EMPLOYEE 테이블로부터 가져온 EMP_ID, EMP_NAME 컬럼 사이의 값을 LOOP를 통해 EMP_ID_TABLE(I)에는 K.EMP_ID의 값을, EMP_NAME_TABLE(I)에는 K.EMP_NAME의 값을 반복 시행으로 삽입하게 된다. 이후 I 변수에 1을 추가하게 되고,  I의 값이 EMP_ID, EMP_NAME의 인덱스 범위를 넘어서면 반복문이 종료된다.
    - 이후 삽입된 인덱스를 J변수를 이용한 FOR문을 통해 출력하게 된다.
    - 아무래도, 자바에 비해 비효율적인 것 같다...
- 레코드 타입의 변수
    - %ROWTYPE처럼 여러 컬럼들을 하나의 변수에 담을 수 있다. 단, 모든 것을 다 포함한 %ROWTYPE과는 달리, 특정 컬럼들을 RECODE() 변수의 () 안에 넣음으로서 지정할 수 있다는 점이 차이점.
        
        ```sql
        DECLARE 
          TYPE EMP_RECORD_TYPE IS RECORD(
            EMP_ID EMPLOYEE.EMP_ID%TYPE,
            EMP_NAME EMPLOYEE.EMP_NAME%TYPE,
            DEPT_TITLE DEPARTMENT.DEPT_TITLE%TYPE,
            JOB_NAME JOB.JOB_NAME%TYPE
          );
          
          EMP_RECORD EMP_RECORD_TYPE;
        BEGIN
          SELECT A.EMP_ID
               , A.EMP_NAME
               , B.DEPT_TITLE
               , C.JOB_NAME
            INTO EMP_RECORD
            FROM EMPLOYEE A
            LEFT JOIN DEPARTMENT B ON (A.DEPT_CODE = B.DEPT_ID)
            LEFT JOIN JOB C ON (A.JOB_CODE = C.JOB_CODE)
           WHERE A.EMP_ID = '&사번' ;
          
          DBMS_OUTPUT.PUT_LINE('사번: ' || EMP_RECORD.EMP_ID);
          DBMS_OUTPUT.PUT_LINE('이름: ' || EMP_RECORD.EMP_NAME);
          DBMS_OUTPUT.PUT_LINE('부서: ' || EMP_RECORD.DEPT_TITLE);
          DBMS_OUTPUT.PUT_LINE('직급: ' || EMP_RECORD.JOB_NAME);
        END;
        /
        ```
        
    - 위 코드의 DECLARE ~ BEGIN절을 통해 EMP_RECORE_TYPE이라는 이름의 변수를 RECORD를 통해 ID, NAME, TITLE, NAME의 4 컬럼을 자료형으로 담은 뒤. EMP_RECORD에 해당 자료형을 할당한 것.
- 예외처리
    - 기존에 오라클에 정의 되어 있는 예외 처리
        
        ```sql
        BEGIN
          UPDATE EMPLOYEE A
             SET A.EMP_ID = '&사번'
           WHERE A.EMP_ID = 202;  
        EXCEPTION
          WHEN DUP_VAL_ON_INDEX THEN
          DBMS_OUTPUT.PUT_LINE('이미 존재하는 사번입니다.');
        END;
        /
        ```
        
    - DUP_VAL_ON_INDEX  라는 이름의, 인덱스 안 값이 중복될 경우 발생하는 예외를 JAVA의 TRAY~CARCH 구문처럼 WHEN ~ END구문으로 감싸 특정 내용을 출력하도록 한다.   (예외 상황시 ‘이미 존재하는 사번입니다.’ 출력)
        
        ```sql
        DECLARE
          DUP_EMPNO EXCEPTION;
          PRAGMA EXCEPTION_INIT(DUP_EMPNO, -00001);
        ```
        
    - 를 통해 -00001 예외(DUP_VAL_ON_INDEX) 를 DUP_EMPNO라는 이름으로 정의할 수도 있다.
    - ORACLE의 각종 예외들 목록:
        - ACCESS_INTO_NULL ORA-06530 LOB과 같은 객체 초기화 되지 않은 상태에서 사용
        CASE_NOT_FOUND ORA-06592 CASE문 사용시 구문 오류
        CURSOR_ALREADY_OPEN ORA-06511 커서가 이미 OPEN된 상태인데 OPEN 하려고 시도
        DUP_VAL_ON_INDEX ORA-00001 유일 인덱스가 있는 컬럼에 중복값으로 INSERT, UPDATE 수행
        INVALID_CURSOR ORA-01001 존재하지 않는 커서를 참조
        INVALID_NUMBER ORA-01722 문자를 숫자로 변환할 때 실패할 경우
        LOGIN_DENIED ORA-01017 잘못된 사용자 이름이나 비밀번호로 로그인을 시도
        NO_DATA_FOUND ORA-01403 SELECT INTO 시 데이터가 한 건도 없을 경우
        NOT_LOGGED_ON ORA-01012 로그온되지 않았는데 DB를 참조할 때
        PROGRAM_ERROR ORA-06501 PL/SQL 코드상에서 내부 오류를 만났을 때, 이 오류가 발생하면 “오라클에 문의(Contact Oracle Support)”란 메시지가 출력됨
        STORAGE_ERROR ORA-06500 프로그램 수행 시 메모리가 부족할 경우
        TIMEOUT_ON_RESOURCE ORA-00051 데이터베이스 자원을 기다리는 동안 타임아웃 발생 시
        TOO_MANY_ROWS ORA-01422 SELECT INTO 절 사용할 때 결과가 한 로우 이상일 때
        VALUE_ERROR ORA-06502 수치 또는 값 오류
        ZERO_DIVIDE ORA-01476 0으로 나눌 때

# 12. PROCEDURE

- 정의
    - PL/SQL문을 저장하는 객체.
    - 필요할 때마다 복잡한 구문을 다시 입력할 필요 없이, 호출을 통해 간단시 싱행시키기 위한 목적으로 사용된다.
- 활용
    - 프로시저의 생성은 아래와 같다. 단, 생성 이후 실행과정을 별도로 진행해야 한다.
        
        ```sql
        CREATE OR REPLACE PROCEDURE DEL_ALL_EMP
        IS
        BEGIN
          DELETE 
            FROM EMP_DUP;
          COMMIT;
        END;
        -- 생성
        
        EXECUTE DEL_ALL_EMP;
        EXEC DEL_ALL_EMP;
        -- 실행 (줄여쓰기 가능)
        ```
        
    - 프로시저는 매개변수를 설정할 수 있다. JAVA의 METHOD와 동작 방식은 동일하다. 매개변수로 변수를 받고 IS~ END 절에서 해당 변수를 사용하는 것.
        
        ```sql
        CREATE OR REPLACE PROCEDURE DEL_EMP_ID
        (
          V_EMP_ID EMPLOYEE.EMP_ID%TYPE
        )  
        IS
        BEGIN
          DELETE
            FROM EMPLOYEE A
           WHERE A.EMP_ID = V_EMP_ID;
        END;
        /
        ```
        
    - SQL의 독특한 특징으로, IN/OUT을 매개변수로 받을 수도 있다. 여러 개의 매개변수를 IN과 아웃으로 구별하여 받은 뒤, IN값을 기준으로 OUT값들을 출력하는 것.
        
        ```sql
        CREATE OR REPLACE PROCEDURE SELECT_EMP_ID
        (
          V_EMP_ID IN EMPLOYEE.EMP_ID%TYPE,
          V_EMP_NAME OUT EMPLOYEE.EMP_NAME%TYPE,
          V_SALARY OUT EMPLOYEE.SALARY%TYPE,
          V_BONUS OUT EMPLOYEE.BONUS%TYPE 
        )
        IS
        BEGIN
          SELECT A.EMP_NAME
               , A.SALARY
               , NVL(A.BONUS, 0)
            INTO V_EMP_NAME
               , V_SALARY
               , V_BONUS
            FROM EMPLOYEE A
           WHERE A.EMP_ID = V_EMP_ID;
        END;
        /
        ```
        
    - SET AUTOPRINT ON; 구문을 통해 PRINT를 별도의 명령 없이 실행 가능하다.
- Cursor
    - 정의
        - 커서는 SQL문이 실행될 때 시스템 메모리에 생성되는 임시 작업 영역이다.
        - SQL커서는 현재 행을 식별하는 포인터와 함께 행 세트를 이룬다.
        - 결과 집합에서 한 번에 한 행씩 데이터를 검색하는 데이터베이스 개체이다.
    - 암시적 커서
        - DML 쿼리를 조작하는 동안 시스템에서 생성되고 사용된다. SELECT 명령으로 단일 행을 선택할 때 암시적 커서 역시 시스템에서 생성된다.
    - 명시적 커서
        - 사용자가 SELECT 명령을 사용하여 생성한다. 명시적 커서는 둘 이상의 행을 포함하지만, 한 번에 하나의 행만 처리할 수 있다. 명시적 커서가 레코드 위로 하나씩 이동하며, 명시적 커서는 행의 레코드를 보유하는 포인터를 사용한다. 행을 결과집합에서 가져온 후, 커서 포인터는 다음 행으로 이동한다.
    - 커서의 주요 구성 요소
        1. 커서 선언(Declare Cursor): 변수를 선언하고 결과 집합을 반환한다.
        2. 열기(Open) : 커서의 입력 부분
        3. 패치(Fetch) : 커서에서 행 단위로 데이터를 검색하는 데 사용된다.
        4. 닫기(Close) : 커서의 종료 부분으로 커서를 닫는 데 사용된다.
        5. (Deallocate) : 할당 해제 : 커서 정의를 삭제하고 관련된 모든 시스템 리소스를 해제한다.
    - 커서의 주요 용어
        - 커서 범위: 
        ● GLOBAL(해당 커서가 연결에 대해 전역임을 지정)
        
        ● LOCAL(해당 커서가 저장 프로시저, 트리거 또는 커서를 보유하는 쿼리에 대해 로컬임을 지정)
        
        - 데이터 가져오기 옵션 : 
        ● FORWARD_ONLY : 커서가 첫 번째 행에서 마지막 행으로만 스크롤될 수 있도록 지정
        
        ● SCROLL :  데이터를 가져오는 6가지 옵션(FIRST, LAST, PRIOR, 
        NEXT, RELATIVE, ABSOLUTE)를 제공한다.
        
        - 커서 유형: 
        ● STATIC CURSOR : 정적 커서는 커서를 만드는 동안 결과 집합을 채우고, 쿼리 결과는 커서의 수명 동안 케시된다. 앞뒤로 이동할 수 있다.
        
        ● FAST_FORWARD : 커서의 기본 유형. 앞으로만 스크롤할 수 있다는 점을 제외하면 STATIC CURSOR와 동일
        
        ● DYNAMIC : 동적 커서에서는 커서가 열려 있는 동안 데이터 소스의 다른 사용자에 대해 추가 및 삭제를 볼 수 있다.
        
        ● KEYSET : 다른 사람들이 추가한 레코드를 볼 수 없다는 점을 제외하면 DYNAMIC과 동일. 타 사용자가 레코드를 삭제할 시 레코드 집합에 엑세스할 수 없다.
        
        - 자물쇠의 종류 :
        → 잠금은 DBMS가 다중 사용자 환경에서 행에 대한 엑세스를 제한하는 프로세스. 행 또는 열이 독점적으로 잠긴 경우 잠금이 해제될 때까지 타 사용자는 잠긴 데이터에 엑세스 불가능. 데이터 무결성을 위해 사용되며, 두 사용자가 한 행의 동일한 열을 동시에 업데이트 불가능하다.
        
        ● READ ONLY : 커서를 업데이트 할 수 없도록 지정
        
        ● SCROLL_LOCKS : 커서에 데이터 무결성 제공. 커서를 사용하여 수행한 업데이트 또는 삭제가 성공할 수 있도록 커서가 행을 커서로 읽을 때 커서가 행을 잠그도록 지정
        
        ● OPTIMITIC : 커서를 읽을 때 커서가 행을 잠그지 않도록 지정. 커서를 사용하여 수행한 업데이트 또는 삭제는 행이 커서 외부에서 업데이트 될 경우 성공하지 못 한다.

# 13. TRIGGER

- 정의
    - 테이블에 DML에 의해 연결될 때 자동으로 수행 할 내용을 정의하여 지정한 객체.
    - 컴파일 후 DML 작업이 실행된 후 작동된다.
    - 행 트리거: 컬럼의 각각의 행의 데이터에 변화가 생길 때마다 PL/SQL 구문이 실행된다. (FOR EACH ROW문 추가)
    - 문장 트리거: 트리거 사건에 의해 PL/SQL 구문이 단 한번만 실행된다. (FOR EACH ROW문 생략)
- 활용
    - 코드부터 보자.
    
    ```sql
    CREATE OR REPLACE TRIGGER TRG_02 AFTER
      INSERT ON PRO_DETAIL
      FOR EACH ROW
    BEGIN
      IF :NEW.STATUS = '입고'
        THEN
          UPDATE PRODUCT A
             SET A.STOCK = A.STOCK + :NEW.AMOUNT
           WHERE A.PCODE = :NEW.PCODE;  
      END IF;
      IF :NEW.STATUS = '출고'
        THEN
          UPDATE PRODUCT A
             SET A.STOCK = A.STOCK - :NEW.AMOUNT
           WHERE A.PCODE = :NEW.PCODE;    
      END IF;  
    END;
    /
    ```
    
    - TRG_02라는 이름의 트리거.
    - AFTER INSERT ON PRO_DETAIL: PRO_DETAIL에 INSERT 작업이 일어난 이후 트리거가 발동한다.
    - FOR EACH ROW: 행 트리거로, 앞선 조건이 실행될 때마다 반복해서 실행된다.
    - 이후 BEGIN ~ END;/를 통해 트리거 발동 시 실행할 구문을 입력한다.
    - 우선, IF~END IF;를 본다면
    :NEW.STATUS = ‘입고’
    구문을 통해 STATUS 값에 입고라는 데이터가 INSERT 될 때, THEN ~ WHERE 구문을 실행하게 된다. 
    마찬가지로, 
    :NEW.STATUS = ‘출고’
    구문을 통해 STATUS 값에 출고 데이터가 INSERT 될 때, THEN~ WHERE 구문을 실행한다.

[명령어](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/%E1%84%86%E1%85%A7%E1%86%BC%E1%84%85%E1%85%A7%E1%86%BC%E1%84%8B%E1%85%A5%20e3bc8ec18c214ac3bfda983b40a56b3b.md)

[Modeling](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/Modeling%206f2ab76d2a004066897556125cfe15f8.md)

[UML](DATABASE%20-%20Oracle%2071bd893ea67442cf9a665c05ac374299/UML%20e8433276c05b4ab48f901bc249a170b7.md)