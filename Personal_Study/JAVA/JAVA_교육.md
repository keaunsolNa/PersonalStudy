# JAVA 교육

<aside>
💡 프로그래밍 언어의 발달 과정은 결핍에 대한 필요에서 시작되었다. 따라서, 공부함에 있어 결핍을 느끼게 된 과정을 이해함이 중요하다. 

과거, 계산기 수준의 프로그래밍 언어에서는 변수값 설정만으로 충분했지만. 하드웨어의 발달에 맞춰 더 복잡한 언어가 필요해진 것이 프로그래밍 언어다. 변수의 범위가 배열로 확장되었고, 배열의 범위가 class, object로 확장되었으며, 그 범위가 package로 확장된 것처럼. 

프로그래밍 언어는 늘 더 많은 필요에 의해 새로운 개념이 필요해졌으며, 그 모든 과정의 흐름을 이해하는 것을 목표로 삼자.

</aside>

# 1. 오리엔테이션

- 팀 플레이의 이유
    - 직업 기초 능력 향상 + 사회적 인맥 형성
    - 링겔만 효과 : 집단에 참여하는 사람 수가 늘어날 수록 1인당 공헌도가 낮아진다. (사회적 태만)
    - 개인보다 비효율적인 팀 작업을 이용하는 이유는 절대적 작업량의 차이 때문
    - 그렇기에 기업은 팀원으로 효율적으로 일할 수 있는 인물을 고용한다.
    - 기업은 갈등이 없는 팀원보단, 갈등을 잘 해결하는 팀원을 원한다.
    - 나 잘난 유형 , 속사포 유형, 완전무결 유형, 권의주의 유형, 나몰라 유형
    - 마크 그라노베터 : 약한 연결의 힘 : 개발자 네트워크는 좁다. 얕은 관계에서 오는 힘이 강하다.
- 팀 프로젝트
    - 규칙 정하기
    - Daily Plan
    - 자가진단
    - Daily Log
- 오리엔테이션
    - HRD 등 수업 관련 오리엔테이션
        - 앞으로의 수업 진행 방향 설정, 상호 예의, 자기 소개, 설문조사 작성
        - 출결, 평가(시험)
    - NCS
        - 지식, 기술, 태도를 기반으로 한 직무에 적응하기 위한 수행능력을 표준화 한 것.
        - 그 한계로 인한 K-Digital 의 방향도 있다.
    - Notion
        - 워드같은 문서작성 프로그램. 앞으로 Notion을 통해 수업 내용 공유 등의 작업이 이루어질 예정
        - /를 통한 다양한 명령어들 (-,—-,토글,h1, 임베드 등)
    - Slack
        - 카카오톡 같은 채팅 프로그램. 앞으로 Slack을 통해 수강생들, 강사님과 소통할 예정
    - 동기부여 영상
        - 하브루타 : 혼자 공부하는게 아닌, 서로 토론하며 공부하는 것. 앞으로도, 특히 Java 수업에서 진행될 예정. 메타인지
        - Logotherapy : 빅터 프랭클. 내 머리가 회색인 것은 내 잘못이 아니지만, 머리를 염색하지 않은 것은 내 잘못이다.
    - Notion 페이지 공유
        - 아이디 수정
        - 수업 과정 강사님에게 공유
    - 설문조사 작성
        - 앞으로의 조 편성을 위한
    - 동기 부여 영상
        - 꿈을 꿈으로 두지 마라
        - OT 마무리
    - 프로그램, 프로그래밍(언어), 프로그래머
        - 많은 프로그래밍 언어 중 자바를 배우는 이유 : 접근성, 점유율, 객체지향에 대한 이해
        - 프로그램 : 컴퓨터가 인식할 수 있는 명령어의 나열
        - 프로그래밍 : 프로그램을 작성하는 과정 = 코딩 (자연어를 프로그래밍언어를 이용해서 기계어로 컴퓨터에게 전달하는 것)
        - 프로그래머 : 프로그램을 작성하는 사람
    - 비합리적 신념
        
        

# 2.기타

- Case
    - 첫 단어를 제외한 다른 단어가 시작될 때 대문자를 사용하는 방식 : camel Case(testProject)
    - 모든 단어가 시작될 때 대문자를 사용하며, 단어와 단어 사이를 _로 구분하는 방식 : Snake Case (Test_Project)
    - 모든 단어를 소문자로 사용하며, 단어와 단어 사이를 -로 구분하는 방식 : Kebob Case(test-project)
    - 모든 단어가 시작될 때 대문자를 사용하는 방식 : Pascal Case(TestProject)
- 에러
    - 컴파일 에러(Compile Error) : 코드의 오류(문법적 오류, Syntax error)로 인해 컴파일러가 컴퓨터 프로그램 소스 코드의 일부를 컴파일하지 못하는 상태, 드물게는 컴파일러 자체의 오류로 인해 발생.
    - 링크타임 에러(Link-time Error) : 리소스나 라이브러리의 부재로 인해 발생하는 오류.
    - 런타임 에러 (Runtime Error) : 프로그램이 동작 중에 어떤 이유로 잘못된 데이터를 가지게 되어 잘못된 처리를 진행하려 할 때 발생하는 오류.
- 아스키 코드
    - 미국정보교환표준부호.
    - 각각의 문자에 맞는 10진수와 16진수가 있다.
    - ‘a’ + ‘b’ = 97 + 98 = 195
    - 문자와 문자의 연산은 정수로 표현된다.
- printf
    - %c : 문자
    %s : 문자열
    %d : 십진수 정수
    %f : 실수
    %.2f : 소숫점 셋째자리에서 반올림해서 소숫점 이하 둘째자리까지 표현 가능

# 3. 프로그램

- 자바 설치
    - Java 파일 설치. (위치 기억, 추후 업데이트 위해)
    - 설치 후 내 컴퓨터의 환경설정 통해 환경변수- 시스템 변수의 path 값에서 Java 설치되어 있는지 확인
    - 위 작업은 어떤 환경에서도 해당 디렉토리에서 자바 작업을 실행한다는 의미 (업데이트 시 환경변수 삭제 후 재설치 필요)
    - 이후 CMD 창(도스 창)에서 “java -version” 입력 (버전 확인)
    - 이후 “javac” 입력 (컴파일러 확인)
    - 이후 C드라이브에 Work 폴더 생성 (이후 작업 디렉토리)
    - 메모장에서 Java 코드 입력 후 [HelloWorld.java](http://HelloWorld.java) 로 저장
        
        ```java
        public class HelloWorld{
        	public static void main(String[] args){
        		System.out.println("Hello World!~~");
        	}
        }
        ```
        
    - print 로도 출력되나, println은 각 문자열마다 엔터키를 누르는 효과
    - 이후 CMD에서 “dir” 명령어로 파일 확인 (”cd..”은 상위 디렉토리 “ work”는 하위 디렉토리인 work로 이동)
    - CMD에서 “javac helloWorld.java” 명령어 통해 컴파일 실행 (디렉토리에 Class 파일이 생성된다.)
    - CMD에서 “java HelloWorld” 입력시 class파일 실행된다.
    
- 자바 개발 환경
    - Java SE (Standard Edition) :  개인용. 순수한 자바. 일반적인 자바.
    - Java EE (Enterprise Edition) : 기업용. 보안이 강하고 업데이트가 빠르다. 단 유료이며 비싸다.
    - Java ME (Micro Edition) :  작은 버전. 애초에는 밥솥 같은 간단한 기능을 위해 Java가 사용되었고, 그 버전.
    - JDK (Java Development Kit) :  JRE를 포함하며, 개발까지 하기 위한 도구.
    - JRE (Java Runtime Environment) : JVM을 포함하며, 자바를 실행만 하기 위한 도구.
    - JVM(Java Virtual Machine)
    - API (Java Class Library) : (JRE를) 실행하기 위한 도구
    - Utilities : (JDK를 통해) 개발하기 위한 도구
    - SE, EE, ME 중 먼저 정한 뒤, 그 안에서 JDK, JRE를 정한다.
- 이클립스
    - 이클립스 설치
        - 이클립스는 컴파일을 자동으로 해 준다.
        - [www.eclipse.org/downloads/](http://www.eclipse.org/downloads/)? : 이클립스 설치 주소
        - 설치 주의 사항  : Eclipse IDE for java EE Developers로 설치
        - 단 자바와는 달리 디렉토리의 폴더 삭제만으로 깔끔하게 삭제 가능하다.
        - C:\Work 에 설치 (.metadata 폴더 설치됨)
        - Eclipse에서 create a project 메뉴 통해 project 생성.
        
    - 이클립스 실습
        - javaproject → JavaSE-11(버전확인)→module체크해제
        - 생성된 폴더의 src 안에 package 생성, package 안에 class 생성
        - ctrl + f11 통해 코드 실행 가능
        - // : 한 줄 주석 :// 뒤의 내용이 주석
        - /* ~ **/ : 범위 주석 :  /**과 */ 사이의 내용(~)이 주석
        - 예제 코드
            
            ```java
            package testProject;
            
            public class TestClass {
            	public static void main(String[] args) {
            
            		// 한 줄 주석
            		
            		/* 범
            		 * 위
            		 * 주
            		 * 석*/
            		System.out.println("하이루");
            	}
            }
            ```
            
    - 디렉토리 구분
        - c:/work → workspace
        - testProject → project
        - testProject → package
        - TestClass → Class
        - Class를 묶어서 package, package를 묶어서 project, project 여러 개가 묶여서 작업하는게 workspace
            
            ![Untitled](JAVA_교육/Untitled.png)
            
        - project - application - process (정확한 표현은 아니지만, 지금 수준에서는 셋이 동일한 맥락이라고 이해해도 좋다.)
            
            
    - 인코딩 방식
        - 2byte로 받아들이면 ms-949 (이클립스 기본 형식)
        - 3byte로 받아들이면 utf-8 (웹은 기본적으로 utf-8 기반)
        - utf-8로 저장 후 ms-949로 불러오면 글자가 깨진다.
        - eclipse에서 변경은 window→preference→general→workspace(디렉토리)→Text file encoding에서 UTF-8로 변경
    
- 이클립스 설정
    - 이클립스 토글
        - 이클리스 상단 메뉴의 Window → Show View 메뉴
        - Minimize 메뉴를 통해 최소화도 가능하다.
    - export와 inport
        - java 파일을 압축하여 저장, 불러오기.
        - 단, 압축 푼 장소가 사용하던 Java workspace가 아니라면 압축 푼 파일을 사용하던 workspace로 옮긴 뒤 불러올 것.
    - 다크/라이트 모드
        
        eclipse에서 상단 메뉴 Window → Preference → Appearance → Theme에서 Dark, Light, Classic, System 선택 가능
        
    - Package Presentation
        - flat 형과 hierarchical : 기본형(flat)과 같은 디렉토리 안의 패키지를 묶는 방법(Presentation)
    - 이클리스 디버그 모드
        - breack point 지정 후, f11로 디버그 모드 진입, 이후 f8로 breack point 코드를 한 번씩 실행하며 확인 가능.
    
- GitLab과 Source Tree
    - GitLab 계정 가입과 Access Tokens (glpat-jyxRMqhRUccPbFF7oCWJ) 발행.
    - Source Tree  계정 생성
    - Git이 모인(hub) 곳이 Github.
    - Github = 형상관리도구
    - Github에서 하나의 어플리케이션을 여러 개의 버전으로 분할(여러 명의 개발자가 작업) 하여 작업.
    - 코드를 올리는 것을 push, 받는 것을 pull.
    - Github에 올려진 코드가 수정되면, 로컬에 있는 코드도 수정된다.
    - 원격을 통해 선생님의 코드 받아올 주소 입력하기.
    - 패치 후 pull 을 통해 소스 코드 받아오기.
- GitLab과 Source Tree로 공유하기
    - Source Tree에 올린 파일을 Git으로 공유하는 방식의 대략적인 설명.
    
     1. 공유할 디렉토리를 하나 만든다.
    
     2. 이클립스로 디렉토리를 파일을 워크 스페이스로 지정한다. (메타데이터 폴더 생성)
    
     3. 생성한 워크 스페이스를 소스트리를 통해 Local Repository로 지정한다. 
    
    1. 그 방법은 소스 코드에서 상단의 +버튼을 눌러 이동, create를 통해 create a repository에서 생성한 디렉토리를 지정함으로서 이루어진다. 
    2. 성공적으로 이루어졌다면, .git이라는 이름의 Local Repository가 생성된다.  (숨긴 파일로 만들어진다.)
    3. 이후, 소스 트리에서 staging area가 확인 가능하다. 
    (왼쪽 사각형이 Local Repository, 우측 아래가 Local Repository에 있는 파일을 읽은 뒤, 우측 위의 Staging area로 올려(add과정) 공유할 파일을 선택 가능하다.  
        
        ![3.png](JAVA_교육/3.png)
        
    4. 이후, eclipse에서 Local Repository에서 project를 생성한다.
    5. 이 때, project 생성시 .gitignore 파일이 생성됨을 확인 하는데, 이 파일을 통해 Local Repository에서 공유하지 않을 파일을 선택 가능하다. (metadata 가 기본값으로 설정되어 있다.)
    6. 이후, Eclipse에서 class 생성 시. 생성된 파일 확인은 물론, 파일의 내용을 변경할 시 변경사항도 확인이 가능하다. 
    7. 이후, GitLab에서 공유할 Project를 생성(New Project → Create blank Project(Project Name 지정과 README 체크 해제) 한다. 
    8. 이후, Clone 메뉴를  통해 HTTPS 주소(원격 저장소, Remote Repository의 주소)를 복사한다. 
    9. 이후, 소스코드에서 우측 상단에 있는 원격 버튼을 통해 원격 저장소를 추가한다. 필요한 정보의 URL 주소에 복사한 주소를 붙여넣은 뒤, 디폴트 원격을 체크. Remote Account에 GitLab 계정을 설정한다.
    10. 추후 다른 원격지를 설정하고 싶을 땐 우측 상단의 설정을 통해 추가 혹은 편집이 가능하다. 
    11. 이후 Staging area에 comment(연도와 시간, 간단한 주석) 를 남긴 뒤, 우측 하단의 커밋 버튼을 통해 Local Repository에 보낸다는 내용을 저장한 뒤. 좌측 상단의 Push 버튼을 통해 저장한 내용을 Remote Repository에 보낸다. (리모트 브런치는 master로 올린다.)
    12. 이후 GitLab의 Graph를 통해 올린 내용, 수정된 내용을 모두 확인 가능하다. 
    
    ![2.png](JAVA_교육/2.png)
    
    - 위 과정의 대략적인 순서도 그림.
    
    ![3.png](JAVA_교육/3%201.png)
    
    - 위 과정에서 사용된 용어들의 대략적인 설명.
- Amateras
    - Class diagram을 편하게 볼 수 있게 도와주는 Plugin

# 4. 자바의 특징

- 자바 언어 특징
    - 운영체제에 독립적
    
    → 한 번 코드를 개발하면 윈도우, 맥, 리눅스 등의 운영체제에 맞는 바이트 코드로 전환해준다.
    
    → 비트는 한 칸 (이진법,0과1), 비트가 8개 모이면 1바이트
    
    → 운영체제에 상관 없이 이식 가능.
    
    → 바이트를 비트로 바꿔주는 것이 운영체제의 역할.
    
    - 다른 언어의 단점을 보완하며 사용하기 쉽다.
    
    → 포인터, 메모리 관리를 사용자가 이해하지 않아도 자바가 시행해준다.
    
    - 풍부한 오픈 소스가 제공된다.
    
    → 다른 사람의 도움을 받을 수 있다.
    
    - 멀티쓰레드(Multi Thread) 환경을 제공한다.
    
    → 쓰레드 : 하나의 일 처리 단위. (단일 쓰레드와 멀티 쓰레드)
    
    → 멀티 쓰레드는 독립적인 일(쓰레드)를 동시 수행 가능하다는 것. 
    
    <aside>
    💡 자바만의 특징은 아니다.
    
    </aside>
    
- JVM(Java Virtual Machine)
    - 자바를 실행하기 위한 가상 기계.
    - Java Code로 파일을 만들면(.java) 자바의 컴파일러가 동작해 .calss 파일(바이트 코드)로 변경한다.
    - 변경된 .class파일(바이트 코드)는 운영체제에서 비트(0,1)로 변경, 컴퓨터에서 인식된다.
    - 위 과정을 진행하는 것이 JVM
    - byte code(class 파일)를 해석하고 실행하는 Interpreter ( Java는 컴파일러이자 인터프리터)
    - 인터프리터 : 한 줄 한 줄 컴퓨터가 즉시 인식 (한 줄 인식 → 한 줄 해석)
    - 컴파일 : 한 번에 변환
    - .java → .class로 변환되는 과정이 compiler. (java는 java compiler를 통해 자동으로 실행되어준다.)
- 자바 설치 (역사)
    - 최초 개발 시에는 오픈 소스였지만, 오라클이 인수한 후 비상업적 용도에서는 무료지만 상업적 용도에서는 유료 구독형 라이선스
    - 기업들은 Open JDK나 Zulu같은 대안 JDK를 고민 한다.
    - https://github.com/ojdkbuild/ojdkbuild : Open JDK  설치 링크 (GitHub)
    - 8버전, 11버전, 17버전이 있고, 세부 업데이트에 따라 달라진다.
    - 버전 자체를 바꾸는 것(업그레이드)을 마이그레이션(Migration)이라고 하나, 비용의 소모가 커 기업에서는 소극적이다.
    - 수업 중에는 open jdk 11.0.14.9-1 버전을 사용.
- Main
    - public static void main(String[] args) 는 main 메소드의 고정 된 선언부
    - 프로그램 실행 시 java.exe에 의해 호출 된다.
    - 모든 클래스가 main메소드를 가지고 있어야 하는 것은 아니지만, 하나의 Java 애플리케이션에는 하나의 main메소드를 포함한 클래스가 반드시 하나는 있어야 한다.
    - public class 클래스 이름 {
    public static void main(String[] args) {메인 메서드의 선언부 / 실행될 문장들을 이 안에 작성}
- 은닉
    - 타입은닉 : 다향성을 이용 참조자료형을 변경하여 타입을 은닉하는 기술.
    - 구현은닉 : 동적 바인딩을 이용 오버라이딩 한 메소드를 사용하는 기술
    - 데이터은닉 : 캡슐화를 이용 다른 메소드를 통해 간접적으로 접근하여 사용하는 기술

# 5. 변수와 상수

- 변수
    - 변수의 정의
        - 변수(Variable) : 변하는 값.
        - 리터럴 : 프로그래밍 언어에서 미리 정의해놓은 값의 형태 (숫자-정수, 실수-, 문자, 문자열, 논리-true,false-)
        - 연산 : 리터럴값들을 직접 산술, 연산할 수 있음. (+,-,*,/,%)
    - 변수 - 자료형
        - 변수(Variable) : 변하는 값, 메모리(RAM)에 시간에 따라 변하는 값을 기록하기 위한 공간
        - 한정된 공간을 효율적으로 사용하기 위해, 박스(type)의 크기는 데이터에 따라 달라진다.
        - 기본적인 표현식, 기본 자료형(type)은 8개로, boolean, char, byte, short, int, long, float, double가 있다. 그 외의, 참조 자료형 중 하나로 String이 있다.
            
            ![1.png](JAVA_교육/1.png)
            
        - 이전에는 메모리의 부족으로, 공간을 효율적으로 사용하기 위해 각 byte단위로 활용했으나, 현재는 메모리의 증가로 int와 double을 기본 형태로 사용한다.
        - 위 데이터 타입 중 String은  참조 자료형으로, 다른 자료형이 변수명을 대입 값으로 지정 하는 것에 비해, 변수명이 있는 위치를 가리키는 화살표와 같은 역할을 한다.
        - 즉, 다른 표준 표현식이 = 라면, String은 → 의 느낌이다. (이를 포인터라고 한다)
        - 참고로, 스트링을 포함한 참조형 표현식의 byte 값은 4 byte다.
    - 문자열과 문자열의 연산.
        - 문자열과 문자열의 +는 붙여쓰기로 취급하며, 그 외의 연산은 작용하지 않는다.
        - -,*,/, 등의 연산 작용을 했을 때, 컴파일 에러가 발생한다.
        - 문자열과 다른 값들의 연산(+)은 붙여쓰기로 취급한다.
        - 문자열이 나온 뒤로, 뒤에 나오는 숫자는 문자열로 취급한다.
        - 문자열과 숫자의 연산이 필요할 때, ()가 필요한 이유
            - System.out.println("10과 20의 합: " + (10 + 20)); = 10과 20의 합 : 30
            - System.out.println("10과 20의 합: " + 10 + 20); = 10과 20의 합 : 1020
            - System.out.println(10 + 20 + “ 10과 20의 합”); = 30 10과 20의 합
        - 정수와 실수의 연산 시, 실수의 범위가 더 넓어 답은 실수로 출력된다. ( 5+5.0 = 10.0)
        → 표현식 참조
    - 변수의 선언
        - 자료형 변수명;
        - 자료형은 boolean, char와 같은 변수의 형식 지정
        - 변수명은 자료형을 가리키는 이름 (기본적으로 중복되지 않는다면 무엇이든 지정할 수 있으나, 암묵적 규칙으로 정해진 변수명들이 존재한다. (byte bNum;, boolean isTure)
            
            ![4.png](JAVA_교육/4.png)
            
        - int sum = 0; 은
        int sum;
        sum = 0;
        과 본질적으로 같다. 전자는 선언과 동시에, 후자는 선언 후에 변수 값을 지정한다는 점이 차이점.
        
        <aside>
        💡 대입 연산자(=)의 실행 방향은 오른쪽에서 왼쪽으로 읽는다.
        
        </aside>
        
    - 변수명 사용 이유
        - 변수를 지정하지 않으면, 리터럴 값이 길어질 수록 인식하기 힘들어진다.
        - 곧, 가독성과 재사용성 증가로 인한 협업과 유지보수성 증가, 유지보수에 용이
    - 값 대입과 리터럴(literal)
        - 리터럴(literal) : 프로그래밍 언어에서 미리 정의해놓은 값의 형태. 숫자(정수,실수), 문자, 문자열, 논리가 있다.
        - =는 대입의 의미
        - 같다는 ==를 쓴다.
        - 박스(변수)에는 하나의 값(리터럴)만 들어간다.
        - int age;
        age = 10;
        age = 20;
        일 때, int age;는 최초 기본 값인 0이었다가, 
        age = 10; 에서 10을 부여받는다
        그 뒤, 10이었던 age 값은 다시 age = 20;에서 20을 부여 받으며, age 값(리터럴)은 10이 아닌 20이 된다.
        - 변수명은 기본적으로 아무거나 넣어도 되지만, 암묵적인 약속에 맞는 변수값이 있다면 변수값을 넣는게 좋다. (s,i,l,f,d 등)
        
        ![6.png](JAVA_교육/6.png)
        
        - long 변수명 뒤의 리터럴에서  L을 붙이는 이유 :  Java에서 정수명은 기본적으로 int값을 사용하기에,  int의 허용값인 4byte 이상의 리터럴 값이 나오면 에러가 발생하게 된다. 하지만 Java에서는 변수명에 정수가 있을 경우, 좌측의 자료형을 int라 가정하고 우측의 변수명을 읽는다.  따라서, 우측의 리터럴값이 int의 범위보다 넓은 정수(22억)가 나올 경우, 좌측의 변수명을 읽기 전에 int값을 초과했다고 여겨 컴파일 에러가 발생한다. 따라서, 우측의 변수명에 long의 약자인 L을 붙여줌으로써, 컴퓨터에게 우항의 리터럴 값이 long 값이라는 것을 인지시킨댜.
        - float 변수명 뒤의 리터럴에서 f를 붙이는 이유 : Java는 표현식의 바이트 값이 큰 값에서 작아진 값으로 이동 시, (float → double) 에러가 발생하게 되어있다. 그러나 Java는 우측의 변수명에 실수가 있을 경우 좌측의 변수명을 double로 가정하기에, float로 다운그레이드가 필요하게 된다. 이 때 오류(컴파일 에러)의 발생을 막기 위해, 우측의 변수명에 float의 약자인 f를 붙여줌으로서, 우항의 리터럴 값이 double이 아닌 float임을 인지시킨다.
        - 단, byte와 short의 경우, 뒤에 약자를 붙이지 않아도 허용해준다. (오류가 발생하지 않는다.)
    - 변수의 명명 규칙(컴파일 에러가 발생)
        - 동일한 범위 내(동일한 {} 안, 동일한 package의 동일한 파일 안에서도 중괄호{}가 다르면 사용 가능하다.)에서 동일한 변수명을 가질 수 없다.
        - 예약어 사용이 불가능하다.
        → 기본 자료형과 같은 Java에서 문법적으로 사용하는 단어들(예약어)은 사용할 수 없다.
            - 예약어
                - Java에서 사용되는 표현식, 함수들.
                - 자주 쓰이는 주요 예약어 목록 (이보다 많다)
                
                ![7.png](JAVA_교육/7.png)
                
        - 대/소문자를 구분한다.
        → 클래스 값과의 착오, 표기법 사용 등
        - 숫자로 시작할 수 없다.
        - 특수문자는 ‘_”와 “$”만을 허용한다.
        → 숫자와는 달리 변수명의 시작과 끝에 있어도 사용 가능하다.
        
        <aside>
        💡 char 자료형은 문자를 넣어도 문자의 유니코드 값을 출력한다.
        
        </aside>
        
    - 변수의 암묵적인 규칙(컴파일 에러는 발생하지 않는다.)
        - 길이의 제한은 없지만 적당한 길이의 변수명을 작성하는 것이 좋다.
        - 합성어일 경우 첫 단어는 소문자로, 두 번째 단어부터는 대문자로(camel-case)
        - 단어와 단어 사이는 언더스코어(_) 사용 하지 말 것.
        - 한글로 변수명을 짓는 것도 가능하지만, 권장하지는 않는다.
        - 변수 안에 저장된 값이 어떤 의미를 가지는지 알 수 있도록 이름을 짓는다.
        - 전형적인 변수명(sum, bNum등)이 있으면 가급적 사용한다.
        
        ```java
        		int sum = 0;
        		int max = 10;
        		int min = 0;
        		int count = 1;
        ```
        
        - 명사형으로 작성한다.
        - boolean형은 의문문으로 작성하되, 가급적이면 긍정형으로 네이밍한다.
- 상수
    - 상수란 원래 변하는 값(변수) 지만, 한 번 상수로 선언을 한 뒤로는 변하지 않는 값(상수)가 된다.
    - 암묵적으로, 상수 값(변수명)은 대문자와 숫자만 적는다. (소문자로 적어도 작동하지만, 변수와 혼동될 수 있어 권장하지 않는다.)
    - 단어와 단어 사이는 언더스코어(_)를 사용한다.
    - 상수의 선언은 final 함수를 사용한다.
    final int VAR = 20;
    - 상수는 결국 변하지 않게 된 변수이다.
    
    <aside>
    💡 한 번 사용했던 변수는 상수로 재선언이 불가능하다. (소문자를 대문자로 바꾸는 등, 변수값의 변경이 필요하다.)
    
    </aside>
    

# 6.데이터와 형변환

- 데이터와 오버플로우
    - 비트(bit) : 컴퓨터가 나타내는 데이터의 저장 최소 단위, 2진수 값 하나를 저장할 수 있는 메모리 공간.
    - 바이트(byte) : 8개의 비트가 모여 하나의 바이트가 된다. 데이터 처리, 문자의 최소 단위.
    - 비트 → 바이트 → 키로 바이트(Kbyte) → 메가 바이트 (Mbyte) → 기가 바이트 (Gbyte) → 테라 바이트 (Tbyte) 순서.
    - 비트 연산
    
    ![제목 없음.png](JAVA_교육/%EC%A0%9C%EB%AA%A9_%EC%97%86%EC%9D%8C.png)
    
    - MSB를 제외하고, 2^6(64) + 2^5(32) + —-2^1(2) + 2^0(1) = 127로, 1byte는 127
    - 128이 되면 MSB에 1이 더해지며, 음수값인 -128로 변한다.
    - 따라서, 1byte의 값은 -128 ~ 127까지.
    - 그러므로, short는 16bit이므로, 2^15인  32,768을 기준으로, -34,768 ~ 32,767의 값을 가지게 된다.
    - 각 자료형의 크기는 다음과 같다.
        
        ![3.png](JAVA_교육/3%202.png)
        
    - 데이터 오버플로우
        - 자료형의 값이 최대 범위를 벗어날 때, 발생한 carry(올림수)를 버림 처리하고 sign bit(MSB)를 반전시켜 최소값(양수는 음수, 음수는 양수)으로 순환시키는 것.
        - 8비트 한계 값인 127에서 1이 더해지면 128이 아닌 -128이 된다. 이를 오버 플로우라고 한다.
        - 마찬가지로, -128에서 1을 빼면 -129가 아닌 127이 된다. 이를 언더 플로우라고 한다.
        - 1의 보수와 2의 보수 : 어떤 수의 ‘n의 보수’는 어떤 수에 더했을 때  n이 되는 수 
        → 1의 보수 : 1로 채워진 2진수에서 빼서 얻은 이진수 또는 비트의 반전
        → 2의 보수 : 1의 보수 + 1. 이진법 계산에서 음수를 의미한다.
- 형변환(Type Casting)
    - 형변환(Type Casting)의 개념
        - 형변환은 자동 형변환과 강제 형변환이 있다.
        - Java에서 정수값은  int 자료형이 기본적으로 사용된다. int의 byte값은 4byte
        - bNum(byte)는 1byte의 크기, -128~127까지의 크기만을 가진다.
        - 따라서 bNum = 128 이상의 값을 지정하면 오버 플로우가 발생한다.
        - 대입 연산자의 실행 방향은 우측의 변수명에서 좌측의 자료형으로 진행되기에,  자료형의 크기(범위)가 변수명보다 크다면 문제가 생기지 않지만, 자료형의 크기(범위)가 변수명보다 작다면 문제(컴파일 에러, 데이터 소실)가 생기는 것.
        - 이 때 문제를 해결하기 위해 형(자료형)을 변환하는 것이 형변환.
            - 즉, 값(Data)의 자료형을 바꾸는 것. (boolean 제외)
            
            <aside>
            💡 실수가 정수보다 범위가 크기에, byte의 크기에 관계 없이 실수 자료형이 정수 자료형보다 큰 사이즈로 취급 받는다.
            
            </aside>
            
            <aside>
            💡 단, 논리형(boolean)은 형변환이 이루어지지 않는다.
            
            </aside>
            
    - 형 변환의 규칙
        - int → float는 같은 4byte지만 사이즈 업
        - float → int는 같은 4byte지만 사이즈 다운.
        - 이는 다루는 자료의 범위의 크기 차이 때문 (실수가 정수보다 범위가 크다.)
        - 그렇기에,  float(4byte) → long(8byte) 역시  사이즈 업이 아니라 사이즈 다운이 된다. (강제 형변환)
        - 작은 사이즈에서 큰 사이즈로 가는 것은 자바가(컴파일러가) 자동으로 해 주기에 자동 형변환
        - 큰 사이즈에서 작은 사이즈로 가는 것은 데이터 소실의 위험(오버플로우)이 있기에 자바 컴파일러가 자동으로 진행하지 않는다. 따라서 사용자가 직접 변경해야 하는데, 이를 강제 형변환이라고 한다.
            
            ![제목 6.png](JAVA_교육/%EC%A0%9C%EB%AA%A9_6.png)
            
        - char의 경우, 유니코드로 전환 시 정수가 되며, java에서는 int로 자동으로 인식하게 된다.
            
            <aside>
            💡 문자(char) 자료형의 경우, 유니코드 변환으로 인해 숫자형으로 계산된다. Java의 정수  기본 자료형은 int이기에, char→int로 전환된다.
            
            </aside>
            
    - 강제 형변환의 방법
        - 자동 형변환(작은 데이터→큰 데이터)의 경우, 자바의 컴파일러가 자동으로 실행하기에 신경쓰지 않아도 된다.
        - 단, 강제 형변환(큰 데이터→ 작은 데이터)의 경우, 자바의 컴파일러가 실행하지 않기에 명령어가 필요하다.
            
            ```java
            int iNum = 1;
            long lNum = 2;
            byte bNum = iNum + lNum;
            ```
            
        - 위 코드의 경우, 우항의 iNum + lNum의 경우 더 큰 크기의 lNum으로 계산하지만, 좌항의 bNum이 byte 크기이기에, 컴파일 에러가 발생하게 된다.  (오버플로우)
            
            ```java
            int iNum = 1;
            long lNum = 2;
            long l1Num = iNum + lNum;
            float fNum = iNum + lNum;
            ```
            
        - 따라서 위 코드처럼, 좌항의 자료형을 우항의 자료형 이상의 크기를 가진 long, float, double로 변경하거나
        
        ```java
        int iNum = 100;
        long lNum = 200;
        byte bNum = (byte)(iNum + lNum);
        
        ```
        
        - 위 코드처럼, 우항의  값을 좌항의 자료형에 맞는 값으로 강제 형변환 해야 한다. (단, 오버플로우로 인한 데이터 손상이 발생한다.)
        → 단, 위 항의 경우, 300이 아닌 44가 출력된다.

# 7. 연산자

- 연산자 (Operator)
    - 산술 연산자
        - + : 더하기
        - - : 빼기
        - * : 곱하기
        - / : 나누기
        - % : 모듈러스(modulus, mod) : 피제수 나누기 제수를 했을 때 나머지
        - 연산에 필요한 값이나 변수가 두 개인 이항 연산자.
        - +, -, *, / , %가 있다.
        
        <aside>
        💡 / 연산 시 형 변환에 유의할 것. (정수 값 사이의 나눗셈도 상수 값이 나올 수 있기에, 오버플로우로 인한 컴파일 에러가 발생한다.)
        
        </aside>
        
    - 복합 대입 연산자
        - 다른 연산자와 대입 연산자가 결합한 것으로, 자기 자신의 값과 연산 후, 연산 결과를 자기 자신에게 누적하여 대입한다.
        - a += 10 ↔ a= a + 10
        - 둘의 의미는 본질적으로 같지만, 우항은 변수를 두 번 찾는 것과 달리 좌항은 변수를 한 번 찾는다. 적은 데이터에서는 큰 차이가 없지만, 빅 데이터에서는 유의미한 차이가 있다.
    - 증감 연산자
        - 피연산자의 값에 1을 더하거나 빼는 연산자. 위치에 따라 결과 값이 달라진다.
        - 전위 연산자 : 먼저 전위 연산 후 다른 연산 실행
        int a = 10;
        int b = ++a;
        → b = 11, a = 11
        - 후위 연산자 : 다른 연산 우선 실행 후 후위 연산 
        int a = 10;
        int b = a++;
        → b = 10, a = 11
        - 다른 연산이라는 것은 대입이나 println을 통한 출력 등을 의미한다. 자세한 사항은 밑의 연산자의 종류와 순서 참조.
    - 비교 연산자
        - 데이터가 같은지, 다른지, 혹은 대소 비교를 할 때 사용
        - 항상 논리 값(true, false)이 결과 값
        - ‘==’ 두 항이 같은가, ‘!=’ 두 항이 같지 않은가
        - >, >=, <, <=도 사용 가능하지만, 기본형 boolean과 참조형 String에서는 사용 불가능하다.
            
            <aside>
            💡 >=  <=, 이상 이하의 부등호의 =는 오른쪽에만 넣는다.
            
            </aside>
            
            <aside>
            💡 boolean 은 리터럴 값으로 true, false만 가능하며, String은 리터럴 값으로 문자열만 가능하기 때문이다. true, false와 문자열은 ==,!=는 부등호가 성립하나, 그 외에는 불가능하다.
            
            </aside>
            
    - 논리 연산자
        - && : 두 피 연산자가 모두 true일 때 true 값 반환 (AND)
        - || : 두 피 연산자 중 하나만 true여도 true 값 반환 (OR)
        - → a : true, b : false일 때. a && b = false, a||b = true.
        
        <aside>
        💡 a || b 에서 b값은 추정 불필요. (연산처리의 효율성 증가, 최적화)
        
        </aside>
        
    - 논리 연결 연산자
        - !를 논리값의 왼쪽에 붙여 사용한다.
        - !true = false, !false = true로 반환한다.
        
        <aside>
        💡 제어문을 활용할 때 자주 사용한다.
        
        </aside>
        
    - 삼항 연산자
        - 자바에서 유일하게 피연산자 항목이 3개인 연산자.
        - 조건식(true, false) ? 식1 : 식2
        - 조건식의 결과 값에 따라 true라면 식 1이 결과, false라면 식2가 결과가 되는 연산자.
        
        ```java
        int result1 = a > b ? a++ : b--;
        ```
        
        - 위의 코드에서, a>b라면 result1의 값은 a가 되며, a는 a++이 되며, a<b 라면 result1의 값은 b가 된 뒤, b는 b—가 된다. (후위연산자)
        - 삼항 연산자 안에 삼항 연산자를 중첩하여 사용할 수도 있다.
        - 연산자의 종류와 순위
    
    ![8.png](JAVA_교육/8.png)
    

# 8. 메소드

- 메소드
    - 메소드 정의
        - 메소드(Method) : 기능. input과 output이 있는 함수.
            
            ![10.png](JAVA_교육/10.png)
            
            - method의 범위는 지금은 빨간색 사각형과 같다고 생각하면 된다. 파란색 사각형은 class의 범위.
        - 메소드는 어떤 특정 작업을 수행하기 위한 명령문의 집합.
        - 메소드의 작동 방식은 요청하고 응답 받는, 함수와 크게 다르지 않다.
        - Java의 메소드에서 전달 값은 있어도, 없어도, 무한대여도 된다.
        - 단, 반환 값(return)은 하나의 타입만이 나온다.
    - 메소드 호출
        - 메소드는 다른 메소드를 호출할 수 있다.
        - 메소드는 return값을 통해 호출한 곳으로 돌아온다.
        이는 STACK 구조라고 하며, STACK 구조는 LIFO(Last-In-First-Out) 특징을 가진다. 
        호출(push)이 쌓일 때, 나중에 쌓인 순서대로 나간다(pop)
            
            ![9.png](JAVA_교육/9.png)
            
            - method가 method1을 호출하고, method1이 method2를 호출 했을 때. method2가 나가기 전까지 method1은 종료되지 않는다.
            
            ![11.png](JAVA_교육/11.png)
            
            - static method가 non static method를 호출할 때, 
            Classname Varname = new Classname();
            Varname.methodname();
            의 형식을 가진다.
            - method 호출의 흐름은 위와 같다.
            - main method에서 시작. 
            main method의 
            Application app1 = new Appliication1();
            app1.methodA(); 
            를 통해 methodA() 호출. 
            methodA()에 있는 methodB() 를 통해 
            method B 호출
            method B 종료 후 void 값을 통해 methodA로 리턴.
            method A 종료 후 void 값을 통해 main method로 리턴
            main method 종료.
    - static과 new. method 호출
        
        ```java
        public static void main(String[] args) {
        }
        public void methodA() {
        }
        
        package com.greedy.section01.method;
        
        public class Application1 {
        	public static void main(String[] args) {
        		
        		System.out.println("main() 시작 됨...");
        		
        		Application1 app1 = new Application1();
        		app1.methodA();
        
        		System.out.println("main() 종료됨...");
        	}
        
        public void methodA() {
        
        		System.out.println("methodA() 호출 됨...");
        	}
        }
        ```
        
        - static이 있으면 자바가 처음부터 method를 인지할 수 있다. static이 없는 method는 별도의 호출이 없다면 java가 인지하지 못 한다.
            - static method는 new 연산자 없이 non static 함수를 호출할 수 없다.
            - non static method는 non static method를 new 연산자 없이 호출할 수 있다.
            - static method 역시, static method를 new 연산자 없이 호출할 수 있다.
        - Application1 app1 : 현재 클래스인 Application1에 app1이라는 변수명을 지정한다.
            - new  Application1(); 을 통해  new 뒤에 나오는 클래스 값을 메모리에 올린다.
            - app1.methodA(); 를 통해 methodA를 호출한다.
            - 이는 Static method 가 non static method를 호출하기 위한 연산자로, non static method에서 non static method를, static method에서 static method를 호출하기 위해선  변수 지정과 new 연산자가 없어도
            methodname(); 연산자를 통해 호출 가능하다.
        - static을 사용하는 이유는 다른 method가 꺼져있다가 호출 할 때만 활성화되는 것과는 달리(호출이 끝나면 다시 종료된다.), static이 method는 프로그램이 시작되는 순간 활성화되어, 프로그램이 종료될 때 까지 계속 활성화되어있기 때문이다.
    - 지역 변수 & Scope
        - 변수는 
        지역 변수
        매개 변수
        전역 변수(필드)
        클래스(static) 변수가 있다.
        - 지역변수는 지역변수가 위치한 메소드 안에서만 작동한다.
        - Scope (Life Cycle) :  Argument가 던진 값이 Parameter에서 실행 된 뒤, Parameter는 종료된다. 그 후 다시 Argument가 다른 값을 던지면, Parameter는 다시 실행된다. 두 Parameter는 별도로 취급된다. (태어나고 죽은 뒤, 다시 다른 것이 태어난 것처럼)
    - Void와 Return
        - void 메소드는 return 값을 명시적으로 작성하지 않아도 마지막 줄에 컴파일러가 자동으로 추가 해 준다. (return 값이 없다는 의미가 아니다.)
        - return; 은 메소드를 강제 종료하고(뒤의 코드는 사용되지 않는다.)  메소드를 호출한 곳으로 돌려주는 역할.  (호출한 곳의 값이 return 값이 된다.)
        - 반환값과 반환 자료형이 일치해야 한다.
        
        ```java
        package com.greedy.section01.method;
        
        public class Application6 {
        	public static void main(String[] args) {
        		
        		System.out.println("main() 메소드 시작함...");
        		
        		Application6 app6 = new Application6();
        		
        		String returnText = app6.testMethod();
        		System.out.println(returnText);				
        		
        		System.out.println(app6.testMethod());		
        		
        		System.out.println("main() 메소드 종료 됨...");
        	}
        	
        	public String testMethod() {
        		return "Hello world";
        	}
        }
        ```
        
        - 위 코드에서, testMethod 메소드의 return값인 “Hello world”가 returnText=testMethod의 값이 된 것.
    - static & non-static
        - static method와 static method는 같은 class 안에 있을 때, class 이름을 생략할 수 있다. (new 연산자를 사용하지 않아도 된다.)
        - 이는 Java는 기본적으로 non static method를 읽어낼 수 없기 때문이다. 따라서, Java가 별도의 연산자 없이 읽어낼 수 있는 static method가 new 연산자를 통해 non static method를 java가 읽을 수 있는 heap 영역에 올리는 과정이 필요해진다. 
        반면, static method의 경우, new 연산자를 통해 해당 method를 heap영역에 올리지 않아도, Java에서 이미 읽어낼 수 있기에 (Static 영역에 있기에) 해당 method를 호출하는 과정만으로도 읽어낼 수 있게 되는 것이다.
        
        ```java
        package com.greedy.section01.method;
        
        public class Application7 {
        	public static void main(String[] args) {
        
        		System.out.println("10과 20의 합: " + Application7.sumTwoNumbers(10, 20));
        		System.out.println("10과 20의 합: " + sumTwoNumbers(10, 20));		
        	}
        
        	public static int sumTwoNumbers(int first, int second) {
        
        		int result = first + second;
        		return result;
        		
        	}
        }
        ```
        
        - 위 코드에서 sumTwoNumbers 메서드는 static method이기에, new 연산자를 사용하지 않고 methodname();의 형식 만으로 method를 호출할 수 있다.
    - 타 class method 호출
        - 하나의 클래스 안에서뿐만 아니라, 다른 클래스에 있는 메소드도 불러올 수 있다.
        - 방법은 메소드 호출과 크게 다르지 않다.
        - static method에서 non static method를 호출할 경우
        ClassName classname = new ClassName();
        자료형 Varname = classname.methodname(argument);
        
        ```java
        Calculator calc = new Calculator();
        		 int min = calc.minNumberOf(argument);
        ```
        
        - static method에서 static method를 호출할 경우
        자료형 Varname = Classname.methodname(argument)
        
        ```java
        int max = Calculator.maxNumberOf(first, second);
        ```
        
- 패키지와 임포트
    - 패키지
        - 서로 관련 있는 클래스 또는 인터페이스 등을 모아 하나의 묶음(그룹)으로 단위를 구성하는 것.
        - 서로 다른 패키지에서는 같은 클래스명 사용 가능.
        - 서로 다른 패키지에 존재하는 클래스를 사용하는 경우에는 클래스명 앞에 패키지명을 명시해서 풀 클래스명을 작성해야 한다. (혹은 import를 사용하거나)
            
            ![2.png](JAVA_교육/2%201.png)
            
        - a패키지와 b 패키지에서 Bear라는 동일한 Class 이름을 사용 가능하다.
        - 다만,  컴퓨터는 a패키지에 있는 Bear와 b패키지에 있는 Bear로 구분하기에, 서로 다른 패키지에 있는 class사용 시에는 클래스명 앞에 패키지의 이름을 붙이거나, Import를 사용하여 패키지의 위치를 선언해야 한다.
    - 임포트(import)
        - 서로 다른 패키지에 있는 클래스나 인터페이스를 사용하는 경우 선언 가능.
        - import (class가 위치한 디렉토리); 
        import 선언은 package와 class 사이의 공간에 자리한다.
        
        ```java
        import com.greedy.section01.method.Calculator;
        ```
        
        - import를 하지 않아도 다른 패키지에 있는 클래스나 인터페이스를 사용할 수는 있다. (단, 총 디렉토리를 붙여야 한다.)
        
        ```java
        com.greedy.section01.method.Calculator calc =
        ```
        
        - 단, java.lang에 있는 method는 워낙 자주 사용하기에, import과정을 거치지 않아도 사용할 수 있다. 단, 이는 어디까지나 예외사항이며, 기본적으로 타 package에 있는 method를 불러오기 위해선 import 과정이 필요하다.

# 9. 제어문

- 조건문
    - 조건문의 개념
        - 조건문은 다중 조건 중 하나의 조건만을 수행한다.
        - 위에서 아래로 가는 프로그램의 순서를 제어하기 위한 것이 조건문의 목적.
    - if문
        - if(조건식){처리해야 할 코드} 형태를 가진다.
        - 조건이 맞으면 처리해야 할 코드{}를 실행한다.
        - 조건이 맞지 않으면 {}안의 코드를 실행하지 않는다.
        
        <aside>
        💡 Java에서 if문은 추가 조건문이 없을 때 {} 생략이 가능하다.
        
        </aside>
        
        ```java
        if (num == 0) 
        {System.out.println("0입니다.");
        }
        ```
        
    - if ~ else문
        - if(조건식) {처리해야 할 코드1) else {처리해야 할 코드2} 형태를 가진다.
        - 양자택일이라고 한다. ( 두 {}중 하나는 시행되어야 한다.)
        - if문을 두 번 사용 시 해당되지 않는 경우 (처리해야 할 코드가 없는 상황) 가 있을 수 있기에 if ~ else 문을 사용한다.
        - if ~ else 문은 if~else if~else문으로 else if 를 추가함으로 조건을 추가할 수 있다.
        - 그 형태는 if(조건식1) {수행될 코드1;} else if(조건식2) {수행될 코드2;} 형태를 가진다.
        - 모든 조건식을 체크하는 if문과 다르게, if~else if~else if~else와 같은 경우, 조건식 1이 맞으면 이후의 조건식은 체크를 하지 않는다. (리소스 감소)
        
        ```java
        if (num % 2 == 0) 
        		{System.out.println("입력받은 숫자는 짝수입니다");
        		} else 
        		{System.out.println("입력받은 숫자는 0이거나 홀수입니다.");
        		}
        ```
        
    - nested if 문 (중첩 if문)
        - 기본적으로, if문을 중복 사용하는 것이 nested if문
        - if (조건식) {
        if (조건식2)  {
        수행될 코드;
        }
        }
        등의 형식을 가진다.
        
        ```java
        if (inputNum>0) {						
        		if (inputNum % 2 == 0) {				
        			System.out.println("입력하신 숫자는 양수이면서 짝수입니다.");
        
        			}
        		}
        ```
        
    - switch문
        - 기본 형식은 아래와 같다.
        
        ```java
        Scanner sc = new Scanner(System.in);
        		int a =sc.nextInt();		
        				
        				switch(a) {
        				case 1 :
        					System.out.println("1이다");
        					break;
        				case 2 :
        					System.out.println("2인데");
        					break;
        				case 3 :
        					System.out.println("3인데");
        					break;
        				case 4 :
        					System.out.println("4인데");
        					break;
        				case 5 :
        					System.out.println("5인데");
        				default : System.out.println("값에서 벗어남.");
        					
        					
        	}
        ```
        
        - switch문은 if문과 달리 조건식 자리에 boolean 이 아니라 정수 또는 문자와 문자열이 가능하다.
        - 조건식의 결과 값과 일치하는 case문으로 이동한다.
        - default문은 일치하는 case문이 없을 때 수행한다. 사용하지 않아도 가능하나 권장되지 않는다.
        - break는 switch문을 종료하는 역할을 하며  (case값이 맞을 때)break가 없을 시 변수를 덮어 씌운다.
        - breack를 사용하지 않을 수 있으며, 이는 윗단계에 아래단계를 포함할 때 주로 사용한다.
        
        <aside>
        💡 if문과의 가장 큰 차이는 case 값으로 범위를 사용할 수 없다는 것에 있다.
        
        </aside>
        
- 반복문
    - 반복문의 개념
        - 프로그램의 순서에서 특정 코드를 반복하는 것.  (종료 시점을 정하지 않아 무한 루프도 가능하나, 좋지 않은 코드. 특정 코드를 특정 시점에 정지하고 다시 프로그램의 순서에 맞춰 진행하는 것이 정석)
    - For문
        - for(초기식; 조건식; 증감식; { 수행될 문장; } 의 형식을 가진다.
        - 초기식 확인 후(초기식이 맞으면) 조건식 확인. 조건식이 true면 문장 수행, flase면 수행하지 않음. 
        이후 증강식 연산 후 조건식 확인. 조건식이 true면 문장 수행, false면 수행하지 않음
        이후 반복.
        
        ![- for문의 기본 형식. ](JAVA_교육/7%201.png)
        
        - for문의 기본 형식. 
        
        ```java
        int sum2 = 0;		
        		for(int i = 0 ; i <= random; i++) {
        			sum2 += i;}
        		System.out.println("1부터 " + random + "까지의 합은 " + sum2);
        ```
        
        - 위 코드의 경우, int i =0이라는 초기식을 확인 후, i의 값이 random 난수보다 작거나 같을 때 sum2변수에 i값을 더한다. 이후 i에 1을 더한뒤, 조건식인 i가 random 난수보다 커질 때 까지 반복한다.
        sum은 누적, 합계라는 의미를 가진 Math API이므로, 더해진 총합이 출력된다.
        
        <aside>
        💡 sum = 누적. 
        sum += i; 의 경우. i변수의 값을 sum에 계속 더한다.
        반복이 언제 종료될 지 알 수 있을 때, 지정할 수 있을 때 주로 사용한다.
        
        </aside>
        
    - 중첩 반복문
        
        ![8.png](JAVA_교육/8%201.png)
        
        - 분침이 다 돌아야 시침이 도는 것처럼, 2번째 for문이 종료된 이후 1번째 for문이 1바퀴 돈 것.
        - for문에 진입 시 수행될 문장 1을 먼저 수행 후, 두번 째 for문에 진입하면 false가 될 때까지 수행될 문장 2를 수행. 이후 수행될 문장 3을 출력 후 다시 첫 번째 for문으로 돌아가 반복.
        - 바깥쪽 for문이 세로,시 안쪽 for문이 가로. 분.
    - While문
        - while(조건식) { 수행될 문장; [증감식 or 분기문];}
        - for문과 거의 유사하다.
        - 가장 큰 차이는 조건식 만으로도 실행될 수 있다는 점.
        
        ```java
        while(!str.equals("exit")) {
        			System.out.println("문자열을 입력하세요: ");
        			str = sc.nextLine();
        		}
        ```
        
        - 기본적으로 위와 같은 형식을 가진다. 조건식이 참일 때, {}안의 “문자열을 입력하세요”를 반복 출력하는 것.
        - 위 코드는 유저가 입력한 Scanner, sc값이 exit가 될 때 까지 문자열을 입력하세요를 반복 출력하는 것.
        
        <aside>
        💡 .equals() : 스트링 문자열 안에 ()안의 문자열이 있는지 확인. 
        반복문의 종료가 언제 될지 알 수 없을 때, 주로 사용한다.
        
        </aside>
        
    - do~while 문
        - 반드시 한 번은 실행된다. 실행 후 true와 false를 판단.
        - 메뉴판과 같은 형식의 어플리케이션을 만들 때 주로 사용된다.
        
        ```java
        		do {
        			System.out.println("문자열을 입력하세요: ");
        		 	str = sc.nextLine();
        			}while(!str.equals("exit"));
        ```
        
        - 위와 같은 형식.
        - 위 do while의 경우, 우선 “문자열을 입력하세요”를 출력한 후. 유저가 exit를 입력할 때 까지 반복한다.
    - for each 문
        - for문과 유사하나, 주로 배열의 처음부터 마지막까지의 인덱스를 모두 반복하는데 사용된다.
        
        ```java
        for (자료형 변수 : 배열) 
        { 
        Ststem.out.println(변수);
        }
        ```
        
        - 위와 같은 형식으로 사용한다. 배열을 처음부터 끝까지 출력한다.
- 분기문
    - break
        - 반복문에서 break문은 자신이 포함 된 가장 가까운 반복문을 빠져나가는 구문이다.
        - 주로 조건문과 같이 사용한다. 반복문을 진행 중, 어떤 조건이 되면 반복문을 빠져나간다.
    - continue
        - 반복문 내에서만 사용 가능하며, 반복문 실행 중 continue 아래 부분을 실행하지 않고 반복문을 다시 시작한다.
        - continue를 만날 시, 조건식이 true면 출력하지 않고 다음 단계로 넘어간다. (for문일 경우 증감식으로 들어간다.)
    - return
        - main method의 반복문에서 return을 입력하면 jvm으로 돌아가기에, main method를 종료할 수 있다.
        - 즉, return은 meain method의 종료를 의미한다.

# 10. Argument & Parameter

- Argument & Parameter
    - 전달인자(argumetn)와 매개변수(parameter)
    - 다른 메소드 간 서로 공유해야 하는 값이 존재할 경우, Varname.methodname(); 의 ()를 이용하여 값을 전달할 수 있다.
    - 이 때 전달하는 값이 전달인자(argument)
    - 전달인자를 받기 위해 선언하는 변수를 매개변수(parameter)
    - 모든 지역변수가 매개변수는 아니지만, 모든 매개변수는 지역변수기도 하다.
    - 컴퓨터는 매개변수와 전달인자는 자료형의 순서가 맞는지(시그니쳐)를 우선적으로 본다. (자료형의 순서로 전달인자를 구분한다.)
    - 지역변수는 선언한 메소드 블럭 내부에서만 사용이 가능하다.
        
        ```java
        package com.greedy.section01.method;
        
        public class Application3 {
        	public static void main(String[] args) {
        				
        		Application3 app3 = new Application3();
        				
        		app3.testMethod(30);						// 30 : argument
        		
        		int age = 20;
        		app3.testMethod(age);
        		
        		byte byteAge = 10;		
        		app3.testMethod(byteAge);
        		
        		long longAge = 80;
        		app3.testMethod((int)longAge);		
        		
        		app3.testMethod(age * 3);
        		
        	}	
        	
        	public void testMethod(int age) {				// int age : parameter이자 지역변수
        		System.out.println("당신의 나이는 " + age + "세 입니다.");
        	}
        
        }
        ```
        
- 매개변수의 종류
    - 매개변수에는 가변인자, 기본 자료형, 클래스 자료형, 기본 자료형 배열, 클래스 자료형 배열이 들어갈 수 있다.
    - 가변인지는 파라미터 영역에 ...을 이용해서 사용한다.
        
        가변인자의 가장 큰 장점은 여러 자료형의 전달인자를 받을 수 있다는 것
        → (자료형... 배열이름)의 형식.
        동일한 자료형의 숫자가 정해지지 않았을 때 유용하게 사용할 수 있다.
        단, 이는 도스를 이용해서 하던 시절의 유산으로, 0부터 무한대에 이르는 배열을 다 받을 수 있다는 장점은 있으나, 현재는 자주 사용되지 않는다. 
        
        ```java
        pt.testVariableLengthArrayParameter("홍길동");
        		pt.testVariableLengthArrayParameter("유관순", "볼링");
        		pt.testVariableLengthArrayParameter("유관순", "볼링", "축구", "당구");
        		pt.testVariableLengthArrayParameter("신사임당", new String[] {"테니스", "서예", "수필"});
        
        public void testVariableLengthArrayParameter(String name, String... hobby) {
        }
        ```
        
    - 기본 자료형은 int, double과 같은 자료형을 파라미터로 받는 것을 의미한다
        
        ```java
        int num = 20;
        		pt.testPrimitiveTypeParameter(num);
        ```
        
    - 클래스 자료형은 객체를 통해 인스턴스화 한 클래스를 파라미터로 받는 것을 의미한다. 레퍼런스, 참조형이라고도 한다.
        
        ```java
        Rectangle r1 = new Rectangle(12.5, 22.5);
        System.out.println(r1);
        
        ```
        
    - 기본 자료형 배열은 int, double과 같은 자료형으로 규정된 배열을 파라미터로 받는 것을 의미한다.
        
        ```java
        int[] iArr = new int[] {1, 2, 3, 4, 5};
        		pt.testPrimitiveTypeArrayParameter(iArr);
        ```
        
    - 클래스 자료형 배열은 실제 값이 아닌 주소값이 들어가있는 배열로, 레퍼런스 변수에 대한 배열이다. 즉, 기본 자료형이 아닌 참조자료형을 이용한 배열.
        
        ```jsx
        classname arrayname[]
        으로 선언 후
        arrayname = new classname[array.length]
        으로 초기화.
        ```
        

# 11. 배열

- 배열의 정의
    
    <aside>
    💡 Java의 배열은 최초의 선언 이후 공간을 늘리거나 줄일 수 없다.
    배열명은 배열의 내용이 아닌, 배열이 있는 위치를 가르키는 변수라고 생각하자.
    
    </aside>
    
    - 동일한 자료형의 변수들을 하나의 묶음으로 다루는 것
    - 배열은 저장된 값 마다 0에서 시작하는 인덱스 번호가 부여된다.
    - 배열은 heap 영역에 new 연산자를 이용하여 크기를 할당한다. (박스 생성)
    
    ![10.png](JAVA_교육/10%201.png)
    
    - int[] arr 은 Stack 영역에 4byte(모든 참조형 자료형의 크기는 4byte다)  크기의 박스를 만든다.
    - new int[3] 은 Heap 영역에 배열의 크기(length)만큼의 박스를 생성한다. 박스의 길이 만큼의 칸마다 0에서 시작하는 변수 값을 지정한다. (지정하지 않으면 각 자료형에 맞는 기본값을 지정한다.) 변수는 변할 수 있는 값이기에, 배열의 내용이 변경되어도 배열은 사용이 가능하다.
        - Heap 영역에 저장된 박스가 있는 위치(ox123,주소는 매번 다르다)를 지정한다. 이는 배열의 첫번째 칸의 위치이며, 나머지 칸의 주소는 Java가 알아서 할당한다.  (사용자가 지정할 수 없다. Java가 포인터를 사용하지 않는 것의 의미)
    - 이후 대입 연산자를 통해, int[] arr 에 new int[0,1,2]의 주소를 가리킨다. (포인터)
        
        <aside>
        💡 배열의 주소를 표현할 때 I@4351bd48 와 같은 형식으로 출력되는데, 이는 int 자료형인4351bd48 주소값이라는 의미이며, 알파벳이 있는 이유는 16진수로 표현하기 때문이다. (16진수는 1부터 a,b,c,d,e까지)
        .hashCode()를 사용하여  배열에서는, 일반적으로 10진수로도 표현 가능하다.
        
        </aside>
        
        <aside>
        💡 배열 값이 null(아무것도 없음)로 지정될 때. NullPointerException 에러가 발생한다.
        
        </aside>
        
- 배열의 선언
    - 자료형[] 배열명 :
    자료형 배열명[];
        - 형식으로 선언한다.
        - 단, 배열의 이름이 길어질수록 []설정이 뒤로 밀리기에 전자가 효율적이다.
    - 정석적인 배열의 선언은
    - 자료형[] 배열명 = new 자료형[] {배열의 길이};으로 이루어진다.
    
    ```java
    int[] arr = new int[]
    int[] arr2;
    ```
    
    - 배열 초기화
    arr[0] = 1;
    arr[1] = 2;
        - 형식으로 초기화 한다.  (배열의 값(변수) 지정)
    - 초기화를 하지 않는다면, 각 자료형에 맞는 초기값으로 지정된다. (배열은 비어있을 수 없다.)
    - 배열에서의 length값을 구하는 경우는 아래와 같다.
        
        ```java
        String str = "Hello World";
        System.out.println(str.length());
        = str이라는 변수명에 있는 글자 수(정수의 숫자)를 물어본다.
        
        arr.length
        = arr이라는 이름의 배열이 몇 칸짜리 배열인지 물어본다.
        ```
        
    - for 반복문에서 arrayname.length;를 이용하여 인덱스의 길이만큼 반복 횟수를 정할 수 있다. 또한, 인덱스를 활용하여 [0], [1], [2] 와 같은 방식으로 출력할 수 있다.
    - 배열의 크기는 동일한 변수명으로 다시 선언할 수 있다.  그 방식은 아래와 같다.
    - int[] dArr;
        - dArr = new double[10];
        - dArr = new double[30];
        - 이는 new 연산자를 통해 10칸짜리 배열을 생성한 뒤, dArr이라는 배열의 이름이 10칸짜리 배열을 가리키고 있는 상태에서 new double[30]을 통해 30칸짜리 배열을 새로 생성한 뒤, dArr 배열명이 30칸짜리 배열을 다시 가리키는 방식으로 배열의 생성이 이루어지기 때문이다.
        - 단, double[10]의 10칸짜리 배열은 더 이상 가리키는 주소가 없기에 heap의 old 영역으로 이동,  G.C(garbage collector)가 해당 배열을 삭제한다. 이후 해당 배열은 다시 참조할 수 없다.
            
            ```java
            int[] a = new int[3];
            		a[0] = 1;
            		a[0] = 3;
            		a[1] = 2;
            		a = new int[4];
            
            		int[] b;
            		b = new int[3];
            		b[0] = 0;
            		b[0] = 4;
            		b = new int[5];
            		
            		int[] c = {1,2,3,4,5};
            
            		System.out.println(Arrays.toString(a));
            		System.out.println(Arrays.toString(b));
            		System.out.println(Arrays.toString(c));
            ```
            
    - 위 코드의 경우, 각각의 출력값은 순서대로 
    {0, 0, 0, 0}
    {{0, 0, 0, 0, 0}
    {1, 2, 3, 4, 5}
    와 같다.
- 2차원 배열
    - 2차원 배열의 정의
        
        ![11.png](JAVA_교육/11%201.png)
        
        - 2차원 배열은 근복적으로 1차원 배열을 저장하는 1차원 배열이다.
        - 2개의 배열을 하나의 배열로 묶을 수 있다. 단, 두 배열 모두 하나의 자료형으로 이루어져야 한다.
            
            ![Untitled](JAVA_교육/Untitled%201.png)
            
        - 2차원 배열의 선언 이후의 흐름은 위와 같다.
        - int[][] arr 을 통해 stack 영역에 int 자료값을 가진 arr라는 이름의 4byte 크기의 박스를 하나 생성.
        - new int[2][3]을 통해 heap 영역에 2칸짜리 배열(1차원배열)을 먼저 생성. 그 후 각각의 배열에 [3]만큼의 배열을 생성.
        - 이후 대입 연산자를 통해 heap 영역의 배열을 의미하는 위치 주소를 int[][]에 부여.
            
            <aside>
            💡 2차원 배열의 첫번째 배열 역시 사용할 수 있다. 이 때, 배열의 호출은 배열명.length; 와 같은 방식으로 활용 가능하다.
            단, arr[i].length; 같은 경우. 첫번째 배열이 2개일 때.
            i=1일 때는 첫번째 배열의 이차원 배열의 숫자.
            i=2일 때는 두번째 배열의  이차원 배열의 숫자.
            
            </aside>
            
        - 아이스크림에 하드류와 소프트크림 류의 2가지 1차 배열이 있고, 하드류에 많은 아이스크림 종류라는 2차원 배열 하나, 소프트크림 류의 많은 아이스크림 종류라는 2차원 배열 하나가 소속되어 있다고 이해하자.
    - 2차원 배열의 선언
        - 자료형[][] 배열명
        자료형 배열명[][]
        자료형[] 배열명[] 으로 가능하나, 첫번째가 권장된다.
        - 1차원 배열과 마찬가지로, 정석은 자료형 배열명[][] = new int[][]; 이나
        자료형 [][] 배열명; 으로 new 연산자는 생략할 수 있다.
            
            ```java
            int arr[][] = new[][];
            int [][] arr1;
            ```
            
        - int[][] arr = new int[3][4] 의 형식으로 이루어진다.
        int자료형에 2개의 배열을 생성, arr라는 배열 명으로 정의한다.
        위 2차원 배열의 경우 3칸이 있고, 그 3칸마다 4개의 칸이 있다는 의미로 정의된다.
        - arr[][] : 기차
        arr{x} : 기차의 x칸
        arr{x}{y} : 기차의 x칸 y자리
        - int[][] arr = {{1, 2, 3, 4}, {5, 6, 7, 8}};과 같은 형식으로 선언과 동시에 초기화가 가능하다.
        정석은, 마찬가지로 int[][] arr = new int[][] {{1, 2, 3, 4}, {5, 6, 7, 8}};의 형식이다.
        - 2차원 배열의 박스 수가 일정하면 정변 배열, 일정하지 않으면 가변 배열이라고 한다.
        - 정변 배열의 선언 형식은 int[][] arr = new int[3][4]와 같고 (3칸의 1차원 배열과, 각 1차원 배열에 4개의 2차원 배열 할당.)
        - 가변 배열의 선언 형식은 int[][] arr =new int[3][] 이후
        arr[0] = new int[4];
        arr[1] = new int[3] 와 같다.
        혹은, int[][] arr= {{1, 2, 3, 4}, {5, 6, 7, 8,9}}; 와 같은 방식으로도 가능하다.
        
        ```java
        int[][] arr = new int[3][4];            // 정변 배열
        int[][] arr2 = new int[3][];           // 가변 배열
        arr2[0] = new int[5];
        arr2[1] = new int[8];
        ```
        
        - 2차원 배열의 1차원 배열 값은 반드시 지정되어야 한다.
- 배열의 복사
    - 얕은 복사(shallow copy)
        
        ![1.png](JAVA_교육/1%201.png)
        
        - 하나의 배열을 서로 다른 이름(배열명)으로 부르는 것.
        - stack에 있는 주소 값만 복사한다.
        - 배열이 새로 생성되는 것이 아닌, 배열의 주소 값을 가리키는 배열명(박스)만 새로 생기고, 두 박스가 같은 배열을 가리키는 개념.
        - 얕은 복사의 활용은 간단하다. 아래의 코드 참조.
            
            ```java
            int[] originArr = {1, 2, 3, 4, 5}; 
            int[] copyArr = originArr;
            ```
            
        - 단, 배열의 선언이 이루어지지 않은 경우, 복사할 배열이 없기에 불가능하다.  아래와 같은 방식으로는 얕은 복사가 불가능한 것.
            
            ```java
            int[] originArr;
            int[] copyArr = originArr;
            ```
            
    - 깊은 복사(deep copy)
        - 개념
            - 새로운 배열 객체를 생성하여 기존 배열의 데이터를 복사하는 것.
            - 얕은 복사와는 달리,  heap 영역에 있는 배열의 값을 특정 범위만큼 복사하여, 새로운 배열명을 생성, 복사 된 배열을 가리키는 것.
            - 깊은 복사는 배열 안의 인덱스 값을 복사하는 방법이 있고,
            배열 안의 특정 인덱스 값 만을 복사하는 방법도 있으며
            배열 안의 첫 인덱스부터 특정 인덱스까지의 값을 복사하는 방법 등이 있다.
                
                <aside>
                💡 arr1 == arr2 가 성립되는 얕은 복사와 달리, 깊은 복사에서는 arr1 == arr2가 성립되지 않는다.
                이는 깊은 복사가 새로운 배열을 만들어내는 개념이기 때문이다.
                또한, arr1과 arr2라는 배열명은 배열이 아닌, 배열이 있는 위치정보를 가리키는 개념이기에, 서로 완전히 같은 인덱스와 값을 가지고 있더라도 두 배열의 위치정보가 다르기에 ≠가 되는 것.
                
                </aside>
                
        - 활용
            - 깊은 복사는 4가지 방법이 있다.
            1. for문과 인덱스를 통한 배열의 값을 복사하는 방법
            2. object의 clone() method를 이용한 복사
            3. System의 arraycopy() method를 이용한 복사
            4. Arrays의 copyOf()를 이용한 복사.
            - for문과 인덱스 활용
                - for문을 활용한 방법의 경우, 코드는 아래와 같다.
                    
                    ```java
                    int[] originArr = new int[] {1, 2, 3, 4, 5};
                    
                    int[] copyArr = new int[originArr.lenght];
                    for(int i = 0; i < originArr.length; i++) {
                    copyArr[i] = originArr[i];
                    }
                    ```
                    
                - int 자료형을 가진 originArr 배열을 생성한 뒤
                originArr 배열의 크기를 copyArr 배열의 크기에 대입한다. 
                이후  for문을 활용, copyArr의 인덱스의 값을 originArr의 인덱스 값과 대입한다.
                    
                    <aside>
                    💡 for each문.은 배열의 처음부터 끝까지 돌아가면서 배열에 있는 값을 하나의 변수에 저장해서 편하게 사용할 수 있다.
                    for(int i : arr1) {} 와 같은 형식으로 사용된다.
                    
                    </aside>
                    
            - Object의 clone() method
                - Object의 clone()을 이용한 복사는 아래와 같다.
                    
                    ```java
                    int[] copyArr2 = originArr.clone();
                    ```
                    
                - int 자료형을 가진 copyArr2라는 배열을 originArr이라는 배열의 클론(복사본)과 대입하는 형태.
            - System의 arraycopy() method
                - 코드는 아래와 같다.
                    
                    ```java
                    int[] copyArr3 = new int[10];
                    System.arraycopy(originArr, 0, copyArr3, 1, 2);
                    ```
                    
                - 우선 copyArr3이라는 배열을 생성한 뒤, 배열의 크기를 설정한다.
                - 이후 System.arraycopy() 명령어를 사용하며, ()안의 내용은 순서대로
                복사하려는 배열의 이름
                복사하려는 배열의 복사할 인덱스 시작 숫자 (originArr의 0번째 인덱스부터)
                복사될 배열의 이름
                복사한 배열의 값이 대입될 복사될 배열의 인덱스 시작 숫자. (copyArr3의 1번째 칸부터)
                복사할 배열의 마지막 범위 숫자 (originArr의 2번째 칸까지)
            - Array의 copyOf() method
                - 코드는 아래와 같다.
                    
                    ```java
                    int[] copyArr4 = Arrays.copyOf(originArr, 3);
                    ```
                    
                - 위 코드는 originArr의 3칸만큼을 복사하여, 복사한 만큼의 coppyArr4를 생성한다.
                - 복사할 coppyArr4의 배열 선언을 Arrays.copyOf() method를 사용하기 전에 할 경우, 사용이 불가능하다. 
                해당 method는 method를 통해 새로운 배열을 복사하여 만들어내는 개념이기 때문.
        
        <aside>
        💡 Arrays.toString() method는 배열의 자료형을 String 자료형으로 변환 후 출력하는 메커니즘을 가지고 있다.
        단, char형과 같은 경우 유니코드 전환 후 유니코드에 해당하는 문자열을 출력하는 것이 아닌, int형으로 출력한다.
        즉, char → int → String의 순서.
        
        </aside>
        
- 배열의 정렬
    - 스위칭
        
        <aside>
        💡 배열의 정렬을 하기 위해서는 스위칭(변환)이 필요하다. 단, 프로그램은 동시에 일어나는 것이 아닌 순서에 따라 진행되므로, 임시 저장소를 만들어 변환되기 전의 값을 저장한 후, 변환하는 과정이 필요하다.
        
        </aside>
        
        - 임시 저장소는 temporary(temp)를 주로 변수명으로 사용한다.
    - 순차 정렬
        - 배열의 처음과 끝을 탐색 하면서 순차대로 정렬하는 가장 기초적인 정렬 알고리즘.
        - 한번에 처음부터 끝까지 진행하는 것이 아닌, 배열의 인덱스에 따라 비교해가며 정렬이 이루어진다.
        - (1칸과 2칸 비교 → 1칸과 3칸 비교 후 2칸과 3칸 비교 → 1칸과 4칸 비교 후 2칸과 4칸 비교 후 3칸과 4칸 비교의 방식으로 이루어진다.)
    - 선택 정렬
        - 배열을 처음부터 끝까지 탐색 한 후, 최솟값을 변수로 지정, 왼쪽부터 채워나가는 방식의 정렬이다.
        - 교환의 회수가 적기에 데이터의 양이 적을 때 효과가 좋다.
        - 하지만 배열을 전부 탐색하여야 하기에 데이터의 양이 커질 수록 효율이 떨어진다.
    - 버블 정렬
        - 인접한 두 개의 원소를 검사하여 정렬하는 방법.
        - 구현이 쉽다는 장점이 있으며, 이미 정렬된 데이터를 정렬할 때 가장 빠르다.
        - 다만 다른 정렬에 비해 정렬 속도가 느리며, 역순으로 정렬할 때 가장 느린 속도를 가진다.
    - 삽입 정렬
        - 배열의 모든 요소를 앞에서부터, 차례대로 이미 정렬된 배열과 비교하여 자신의 위치에 삽입하여 정렬한다.
        - 배열의 두 번째 데이터부터 연산을 시작한다.
        - 버블 정렬의 비교 횟수가 많은 단점을 개선하기 위해 고안된 정렬 방식.
    
    <aside>
    💡 그 외의 많은 방식의 정렬이 있다.
    
    </aside>
    
- 객체 배열
    - 객체 배열은 실제 값이 아닌 주소값이 들어가 있는 배열. 레퍼런스 변수에 대한 배열이다.
    - 즉, 기본 자료형이 아닌 참조 자료형을 이용한 모든 배열이 바로 객체 배열이다.
    - 배열의 선언은 
    클래스명[] 배열명;
    로 이루어진다.
    - 할당은 
    배열명 = new 클래스명[배열크기]
    으로 이루어진다.
    - 선언과 동시에 할당은
    클래스명 배열명[] = new 클래스명[배열크기]
    으로 이루어진다.
    - 객체배열은 2차원 배열의 1차원 배열도 크기가 일정하지 않을 수 있다.
    이는 객체가 다양한 자료형을 담을 수 있기 때문이다.
    - 객체배열을 사용하는 가장 큰 장점은 하나의 자료값만 사용할 수 있는 배열과 달리, 객체 배열을 통해 다양한 자료형의 데이터들을 하나의 인스턴스로 묶고, 각각의 인스턴스를 배열화 함으로서 관리를 편하게 할 수 있다는 점에 있다.

# 12. 객체

<aside>
💡 객체지향의 핵심은 
적절한 책임을 수행하는 객체들간의 유연하고 견고한 협력

</aside>

- 객체, 객체 지향의 의미.
    - 객체지향의 3대 특성은 상속, 다형성, 캡슐화다.
    - 객체 지향의 개념은 사람의 생각과 컴퓨터(기계)의 생각 사이의 차이를 해소하기 위해 만들어졌다.
    - 객체는 사람이 세상을 받아들이는 방식. 사람은 세상의 모든 것을 객체로 구분하여 이해한다. 객체와 객체 사이의 상호작용이 사람이 세상을 받아들이는 방식.
    - 현실 세계는 사물이나 개념처럼 독립되고 구분되는 각각의 객체로 이루어져 있으며, 객체와 객체 사이에서 발생하는 모든 사건들은 상호작용이다.
    - 그 상호작용을 프로그래밍적으로 해석한 것이 곧 객체지향언어.
    
    <aside>
    💡 내가 아는 개념을 컴퓨터에게 자료형으로 인식 시키는 것이 객체의 핵심.
    
    </aside>
    
- 객체 지향 언어의 규칙과 특징
    - 단일 책임의 원칙.
        - 하나의 범위에서 생기는 문제 (속성의 변경, 변수의 변경과 같은)는 그 범위 안에서 해결해야 한다.  이는 객체와 객체는 메세지를 통해 서로 상호작용을 하는데, 메세지를 받는 수신자는 메세지를 전달 받아 그 메세지에 해당하는 내용을 처리하는 방법을 스스로 결정하고, 그 결정한 방법대로 명령어들을 처리하는 것을 의미한다.
        - 이와 같은 방법론, 상호작용의 원칙이 책임주도 설계이며. 송신자와 수신자가 전달 받은 메세지의 내용을 처리하는 방법을 송신자와 수신자 안에서 해결하는 것이 곧 단일 책임의 원칙이다.
    - 접근제한자
        - 공개 범위의 순서대로 public → protected → (default) → private 가 있다.
        - 접근제한자는 주로 캡슐화를 하기 위한 방법으로 사용되며, 각자가 책임지는 범위를 한정하는 방식으로 사용된다.
        
        ![6.png](JAVA_교육/6%201.png)
        
        - 필드 접근제한자의 종류와 그 범위.
        
        <aside>
        💡 default의 경우, private나 public과는 달리, 캡슐명을 적지 않는다. (오히려 적으면 에러가 생긴다.)
        
        </aside>
        
    - 캡슐화(Encapsulation)
        - 캡슐화는 결합도를 낮추기 위해 필드의 직접 접근을 제한하고 public method를 이용해서 간접적으로 접근하여 사용할 수 있도록 만든 기술이다.
        - 결합도를 낮추는 이유는 직접 접근할 수 있는 범위를 캡슐화된 범위 만으로 한정 지음으로써(단일 책임의 원칙), 유지보수성을 높이기 위함이 첫 번째 목적이다..
        - Getter 와 Setter
        캡슐화 클래스 외부의 변수 값을 검색하고 업데이트 하는 역할을 한다
        setter는 변수 값을 업데이트 하며,
        getter는 업데이트 한 변수 값을 읽는다.
    - 추상화
        - 대상(정보의) 본질적인 부분만을 추려낸 것.
        - class는 기능에 맞춰 속성을 추가한다. (도서 검색 기능을 위해, 카테고리와 작가, 가격이라는 속성을 추가하는 것. )
        - 유연성을 확보하기 위해 구체적인 것은 제거한다는 의미.
        - 유연성 확보란 여러 곳에 적용될 수 있는 유연한 객체, 즉 재 사용성이 높아진다는 것이다.
        - 추상화란 곧 프로그램에서 필요한 공통점을 추출하고, 불필요한 공통점을 제거하는 과정.
        
        <aside>
        💡 유연성이 높으면 결합도가 낮고, 결합도가 높으면 유연성이 낮다.
        
        </aside>
        
    - 오버로딩(Overloading)
        - method 이름이 같은 메소드를 계속해서 쌓을 수 있는 기술. (println 역시 오버로딩을 활용한 method다.)
        - 동일한 메소드명으로 매개변수의 타입, 개수, 순서(시그니쳐, Signature)를 다르게 하면 계속 쌓을 수 있다. 변수명은 고려사항이 아니다.
        - 오버로딩의 사용 이유는, 매개변수의 종류별로 메소드의 내용을 다르게 작성해야 하는 경우, 동일한 이름으로 다양한 종류의 매개변수에 따라 처리해야 하는 여러 메소드를 동일한 이름으로 관리하기 위해 사용된다.
- 생성자(Constructor)
    - 객체가 new 연산자를 통해 Heap 메모리 영역에 할당될 때 1회성으로 호출되는, 리턴 타입이 없는 메소드.
        - 여기서 1회성 호출이라 함은, 동일한 이름의 메소드를 여러 번 호출하여 동일하게 사용할 수 있는 것과는 달리, 생성자는 한 번 생성된 뒤 다시 불려지지 않는다는 것을 의미한다. (마치 게임을 킬 때 처음 나오는 문장처럼)
    
    ```java
    [접근제한자] 클래스명() { }
    public className {}
    
    [클래스명] [레퍼런스변수] = new [클래스명]();
    ```
    
    - 생성자는 위와 같은 형식으로 생성된다.
    - 또한, 아래와 같은 형식으로 호출된다.
    - new 뒤의 [클래스명]은, 생성자가 클래스와 동일한 이름을 가져야 하기에, new [생성자]라고 받아들여도 무방하다.
    - 인스턴스를 생성할 때 초기 수행할 명령이 있는 경우 미리 작성해 두고, 인스턴스를 생성할 때 호출 된다.
    - 생성자는 매개변수가 없는 기본 생성자, 매개변수가 있는 생성자로 나뉜다.
    - 지금까지 Scanner method나, 다른 클래스의 method를 불러올 때도 생성자는 JVM에 의해 자동으로 생성되어 왔었다. 다만, 기본 생성자의 경우 명시적으로 작성하지 않아도 작동되었을 뿐. 다만, 상속이나 spring을 사용함에 있어 자동으로 추가되지 않을 경우 에러가 발생할 수 있기에, 앞으로는 기본적으로 생성자를 추가하도록 하자.
    - 인스턴스 생성 시 필드 초기화 목적으로 주요 사용된다.
    - 생성자명은 클래스명과 동일해야 한다.
    
    ```java
    public Academy() {}
    ```
    
    - 기본 생성자는 위와 같은 방식으로 선언된다. 여기서 Academy는 Class name과 동일해야 하며, void와 같은 리턴 값을 받는 method를 사용하면 안 된다.
    - 단, 타 패키지의 class name을 지정할 경우에는, import를 통해 디렉토리를 인식시켜줘야 한다.
    
    <aside>
    💡 생성자의 this.는 class에서의 this.와 달리, 생성될 인스턴스를 가리킨다.  즉, 같은 클래스의 다른 생성자를 가리킨다.
    
    </aside>
    
    <aside>
    💡 new java.util.Date(); 
    현재 컴퓨터의 시스템 시간을 가진 Date instance 생성
    
    </aside>
    
- class
    - class의 개념
        - 클래스는 서로 다른 자료형의 데이터들을 사용자 정의로 하나로 묶어 새로운 타입을 정의하는 것.
        - 클래스의 필드에 접근하기 위해서는 온점(.)을 이용한다. (참조연산자)
        온점을 통해 해당 객체로 접근한 뒤, 객체에 이름을 부여한다.
        - 단, 필드에 직접 접근, 즉. 클래스를 구조체처럼 다룰 경우의 문제는 잘못 된 값이 대입 되거나(버그 발생 위험), 필드 명이 변경되면 관련 코드를 모두 수정해야 한다는(유지 보수의 어려움) 단점이 있다.
        → 이 때 사용되는 것이 this.
        - class의 가장 큰 핵심은, 컴퓨터에게 클래스 이름에 해당하는 개념을 준다는 것. 또한, 클래스 이름을 하나의 자료형으로 받아들인다는 것.
    - class 호출
        
        ![2.png](JAVA_교육/2%202.png)
        
        - new 연산자를 통해 개념이었던 class에 실체를 부여해주는 것.
        - Class와 동일한 이름의 method를 new연산자를 통해 생성할 때,  mtehod는 기존의 동일한 이름의 class를 가리키게 되는데, 이를 인스턴스화라고 한다.
        - 호출이라는 과정을 통해 개념에 실체를 부여한다. 실체를 부여해주는 과정은 heap 영역에 new 연산자를 통해 classname의 인스턴스를 올리는 것.
        - 위 이미지의 경우, new 연산자를 통해 Member 클래스를 m이라는 이름으로 인스턴스화 한다.
        - 이후, m.varName을 통해, Member 클래스에서 정의된 자료형
        String id;
        String pwd; 등
        의 값을 지정하게 된다. 
        m. id = “u01”; 의 경우, Meber 클래스의 String 자료형을 가진, id라는 변수를 “u01”로 만드는 것.
        
        <aside>
        💡 new를 할 때 마다 객체가 생성된다.
        
        </aside>
        
    - class에서의 this.
        - 매개변수를 가지는 생성자에서 매개변수 명이 필드명과 같은 경우, 매개변수의 변수명이 우선이므로 this를 이용하여 필드라는 것을 구분 해 주는 역할을 한다.
        - 필드에 직접 접근 시 생기는 문제점들을 해소하기 위해, 우회로를 마련하는 과정에서 필요성이 생긴다.
        - this. 는 곧 method를 호출한 객체.
        
        ```java
        Monster monster3 = new Monster();
        		monster3.name = "드라큘라";
        		monster3.setHp(200);
        ```
        
        ```java
        public class Monster {
        	
        	String name;							// 몬스터 이름
        	int hp;									// 몬스터 체력
        	// 속성
        	
        	public void setHp(int hp) {
        		if(hp >= 0) {						// 0또는 양수일 때
        			System.out.println("양수값이 입력되어 몬스터의 체력을 입력한 값으로 변경합니다. ");
        			this.hp = hp;					// monster4가 호출하는 순간, this.는 monster4가 된다.
        		} else {							// 음수일 때
        			System.out.println("0보다 작거나 같은 값이 입력되어 몬스터의 체력을 0으로 변경합니다.");
        			this.hp = 0;
        		}
        }
        ```
        
        - 위 코드의 경우, Monster monster3 = new Monster(); 라는 과정을 통해, monster3 라는 이름의, Monster class에 있는 객체를,  new라는 연산자를 통해 heap 영역에 생성한다(인스턴스화).
        - 이후 [monster3.name](http://monster3.name) = “드라큘라”; 라는 과정을 통해,
        Monster 클래스에 있는 name 이라는 객체에 “드라큘라” 라는 값을 지정한다.
        이후, monster3.setHP(200); 이라는 과정을 통해 public void setHp(int hp)에 접근한다. 
        접근한 이후, if문을 통해 다른 결과를 출력하게 한다. (우회)
        위 경우, 양수이기에 this.hp = hp;의 과정을 시행한다.
        이 때, this는 해당 과정을 (method를) 호출한 monster3 가 된다.
        곧, monster3는 hp라는 argument가 되기에, 200이라는 양수 값이 된다.
            
            ![5.png](JAVA_교육/5.png)
            
            - 위 과정의 이미지.
    - class에서의 변수
        - 클래스 변수
            - static 키워드를 가지고 필드에 선언하는 변수. 메모리의 static 영역 사용
            - 프로그램 시작 시 생성되어 프로그램 종료 시 소멸한다. 프로그램과 Life Cycle을 같이 한다.
        - 멤버 변수(인스턴스 변수)
            - static 키워드 없이 필드에 선언하는 변수. 메모리의 heap영역 사용
            - 인스턴스 생성 시 생성되어 참조하지 않을 시 소멸한다. 인스턴스와 Life Cycle을 같이 한다.
        - 지역 변수
            - 메소드, 생성자, 초기화 블록 내부에서 선언하는 변수. 메소드 호출 시 생성되어 메소드 종료 시 소멸한다. { } 중괄호와 Life Cycle을 같이 한다.
- final filed
    - final은 상수, 변경 불가의 의미를 가진다.
    - 따라서 초기 인스턴스가 생성될 때 기본값 0이 필드에 들어가게 되는데
    - 그 초기화 이후에 값을 변경할 수 없어 filed변수를 final로 선언 할 때에는 선언과 동시에 초기화를 진행 해줘야 한다.
    - 단, non static final field의 경우, 생성자를 이용해 초기화가 가능하지만, static final field의 경우 생성자를 이용한 초기화가 불가능하고, 선언과 동시에 초기화하는 방법만 가능하다.
    - 이는 static의 경우 new 연산자를 통해 heap 메모리 영역에 올리는 과정 없이 Java가 인식할 수 있기에, 선언과 동시에 초기화를 하지 않는다면 선언시 JVM에 의해 초기값이 설정되기 때문이다. 상수는 한 번 선언 후 재설정이 불가능하므로, JVM이 초기값으로 상수 선언은 의미가 없다고 판단하여 컴파일 에러가 발생하는 것.
    
    ```java
    private final int NONSTATICNUM = 1;
    private final int NONSTATICNUM;
    public FinalFiledTest() {
    		NONSTATICNUM = 1;
    ]
    
    private final static int STATICNUM = 1;
    ```
    
    - 위가 non static final field, 아래가 static final field
- 초기화 순서
    - 인스턴스 변수는 다음과 같은 순서로 이루어진다
    JVM 기본값 → 명시적 초기값 → 인스턴스 초기화 블럭 → 생성자
    - 클래스 변수
    JVM 기본값 → 명시적 초기값 → 정적 초기화 블럭 → 인스턴스 초기화 블럭 → 생성자
    - 각각 JVM이 초기화 이전 선언을 통해 초기값을 대입해주는 초기화 → 필드 변수 영역에서 정의하는 초기화 →필드 영역에서 { } 중괄호를 통해 진행하는 초기화 (인스턴스 초기화 블럭) →  static 영역에서 따로 진행해주는 초기화 → 생성자를 통한 초기화이다.
    - 그 순서에 대한 코드를 통한 도식화는 아래와 같다.
    
    ```java
    package com.greedy.section08.initblock;
    
    public class Product {
    
    	/* 0. JVM이 각각 초기화를 해줌 */
    //	private String name;
    //	private int price;
    //	private static String brand;
    
    	/* 1. 명시적 초기화를 통해 초기화를 할 수 있다. */
    	private String name = "갤럭시";
    	private int price = 10000000;
    	private static String brand = "샘숑";
    
    	
    	{
    		name = "사이언";
    		Product.brand = "헬지";
    		System.out.println("인스턴스 초기화 블럭 동작함...");
    	}
    	
    	static {						// static 영역만 초기화 한다.
    //		name = "아이뽕";
    		Product.brand = "사과";
    		System.out.println("정적 초기화 블럭 등장함...");
    	}
    	
    	public Product() {
    		name = "투명폰";
    		System.out.println("객체 생성한답니다.");
    	}
    
    	@Override
    	public String toString() {
    		return "Product [name=" + this.name + ", price=" + this.price
    				+ ", brand=" + Product.brand + "]";
    	}
    }
    ```
    
    <aside>
    💡 이 중 인스턴스 초기화 블럭은 도스 시절에 사용되던 초기화 블럭으로, 현재에는 사용될 일이 거의 없다.
    
    </aside>
    
- DTO(Data Transfer Object)
    - 객체는 여러 계층에 걸쳐 다양한 기능을 하게 되는데, 이 때 DTO는 계층마다 있는 정보를 옮기는 역할을 한다.
    - 기본생성자 →매개변수 생성자→getter setter→toString 순으로 생성한다. 이 순서는 Java bean의 형식이기도 하다.
        - 자바 빈(Java Bean)
            - 자바 코드를 모르는 웹 퍼블리셔들도 자바 코드를 사용할 수 있도록 태그 형식으로 지원하는 문법으로, 규칙을 지정해 놓은 java 클래스이다.
            - 자바빈의 규칙은 5가지로, 아래와 같다.
            1. 자바빈은 특정 패키지에 속해 있어야 한다. (단, default 패키지는 사용 금지)
            2. 멤버 변수의 접근 제어자는 private로 선언되어야 한다. (캡슐화 적용)
            3. 기본 생성자가 명시적으로 존재해야 한다.(매개변수 있는 생성자는 선택 사항이다.)
            4. 멤버 변수에 접근 가능한 설정자(setter)와 접근자(getter)가 public으로 작성되어 있어야 한다.
            5. 직렬화(Serializable)가 되어야 한다. (단, 이는 선택사항이다.)
- 개념(concept)
    - 일반적으로 인식하고 있는 다양한 사물이나 객체에 적용할 수 있는 아이디어나 관념
    - 개념을 이용하여 객체를 여러 그룹으로 분류(classification)할 수 있다.
    - 개념은 아래의 3가지 관점을 통해 구성된다.
    1. 심볼(symbol) : 개념을 가리키는 간략한 이름이나 명칭
    2. 내연(intension) : 개념의 완전한 정의를 나타내며 내연의 의미를 이용해 객체가 개념에 속하는지 여부를 확인할 수 있다.
    3. 외연(extension) : 개념에 속하는 모든 객체의 집합(set)
- 타입(Type)
    - 근본적으로 개념(concept)과 동일하다.
    1. 어떤 객체가 어떤 타입에 속하는지를 결정하는 것은 객체가 수행하는 행동이다. 어떤 객체들이 동일한 행동을 수행할 수 있다면 그 객체들은 동일한 타입으로 분류될 수 있다.
    2. 객체의 내부적인 표현은 외부로부터 철저하게 감춰진다. 객체의 행동을 가장 효과적으로 수행할 수만  있다면 객체 내부의 상태를 어떤 방식으로 표현하더라도 무방하다.
- 분류(classification)
    - 특정한 객체를 특정한 개념의 객체 집합에 포함시키거나 포함시키지 않는 작업.
    - 어떤 객체를 어떤 개념으로 분류할지가 객체지향의 품질을 결정한다.

<aside>
💡 객체란 특정한 개념을 적용할 수 있는 구체적인 사물을 의미한다. 개념이 객체에 적용됐을 때 객체를 개념의 인스턴스라고 한다.

</aside>

# 13. 상속(Inheritance)

- 상속의 개념과 형식
    - 개념
        - 부모클래스가 가지는 멤버(Java에서는 클래스가 가지는 필드나 메소드)를 자식클래스가 물려받아 자신의 멤버인 것처럼 사용할 수 있도록 만든 기술
        - 멤버 외에 타입 또한 상속이 된다. (다형성의 토대)
        - 자바는 단일 상속(하나의 클래스만 부모로 삼는다.) 만 지원
        - 클래스 간의 상속 시에는 extends 키워드를 사용한다.
        - 단, 자식은 하나만 만들어야 하는 것이 아니다.
        - 부모(super)와 자식클래스로 나뉜다.
        - 상속은 자식이 부모를 기리킨다.
        - 상속은 
        [접근제한자] [class] [자식 클래스 이름] extends[부모 클래스 이름]{};
        으로 사용한다.
        - 자식클래스를 호출 시 부모클래스가 우선 호출되는데, 이는 자식 클래스가 부모 클래스의 인스턴스를 자식 클래스 안에 포함하고 있기 때문이다.  
        그렇기에 자식클래스의 기본 생산자를 작성 시, 생략되지만 실제로는 super(); 라는  연산자를 통해 해당 생산자가 누구로부터 상속 받았는지를 확인 가능하다.
        
        <aside>
        💡 단, 생산자는 상속되지 않는다. DTO시 super()를 this()로 대체할 수 없는 이유.
        
        </aside>
        
    - 형식
        - 아래의 두 class가 있고, 아래 클래스가 위 클래스를 상속하는(extends) 개념일 때
        
        ```java
        public class Shape {
        	private double area;
        	public void calcArea() {
        		// 넓이 구하기 메소드
        	}
        ]
        ```
        
        ```java
        public class Circle extends Shape {
        	private int x;
        	private int y;
        	private int r;
        }
        ```
        
        - Circle is a Shape 
        상속을 위해서는 위와 같은, is a 관계가 성립되어야 한다.
        
        ![7.png](JAVA_교육/7%202.png)
        
        - 클래스 인스턴스 생성은 위와 같다. 자동차라는 인스턴스를 복사하여 소방차와 경찰차라는 인스턴스를 생성하는 것.
        - 반면 상속의 개념은 아래와 같다.
        자동차라는 부모가 가지고 있는 여러 개념들을 소방차, 경찰차라는 자식 클래스가 포함하는 것.
- 상속의 장단점
    - 장점 : 새로운 클래스 작성 시 기존에 작성 된 클래스의 재사용이 가능하다.
    클래스간 계층 관계가 형성되어 다향성 문법의 토대가 됨.
    - 단점 : 부모클래스의 기능 변경 시 자식클래스의 정상적인 동작 유무 예측이 힘들다.
    - 자식클래스가 물려받아 사용하는 기능들을 부모 클래스에서 변경 시 어려움이 따른다.
    - 부모 클래스로부터 물려받은 기능이 자식클래스에서는 무의미 할 수 있다.
        
        장단점을 고려해 볼 때IS-A  관계로 구분되는 경우에만 사용해야 한다.
        
- super. 와 super()
    - super : 부모 클래스의 인스턴스 주소를 보관하는 레퍼런스 변수다.
    자식 클래스 내의 모든 생성자와 메소드 내에서, 부모 클래스의 레퍼런스 변수를 사용할 수 있다.
    - super() : 부모 생성자를 호출하는 키워드로 매개변수의 타입, 개수, 순서가 일치하는 부모의 생성자를 호출하게 된다.
    this()는 해당 클래스의 생성자를 호출하는 키워드다.
    super()는 부모클래스가 가지는 private 생성자를 제외한 나머지 생성자를 호출하는 키워드다.
    그렇기에, 아래의 코드에서 super를 this로 대체할 수 없다. (컴파일 에러 발생)
        
        ```java
        public ComputerDTO(String code, String brand, String name, int price, Date manufacturingDate, String cpu, int hdd,
        			int ram, String operationSystem) {
        		super(code, brand, name, price, manufacturingDate);
        		this.cpu = cpu;
        		this.hdd = hdd;
        		this.ram = ram;
        		this.operationSystem = operationSystem;
        ```
        
    - super. : 부모 필드도 포함한 모든 필드를 초기화 하는 생성자로 인스턴스 생성 후 정보를 출력하려 할 경우, 매개변수로 부모와 자식의 모든 데이터타입을 입력해도 자식 클래스의 내용만이 출력된다. 따라서, 자식 클래스의  toString DTO의 return 값으로 super.toString을 붙여줌으로서, 출력하는 toString에 부모의 toString을 추가하는 방식으로 활용해야 한다.  그 방식은 아래와 같다.
        
        ```java
        public String toString() {
        		return super.toString() + "ComputerDTO [cpu=" + cpu + ", hdd=" + hdd + ", ram=" + ram + ", operationSystem=" + operationSystem
        				+ "]";
        	}
        ```
        
- 오버라이딩(Overriding)
    - 부모클래스에서 상속받은 메소드를 자식클래스가 재정의하여 사용하는 것.
    - 오버라이딩은 다음의 규칙을 지켜야 한다.
    1. 메소드 이름 동일
    2. 메소드 리턴 타입 동일
    3. 매개변수의 타입, 개수, 순서가 동일
    4. private 메소드는 오버라이딩 불가능.
    5. final 키워드가 사용된 메소드는 오버라이딩 불가능
    6. 접근제한자는 부모 메소드와 같거나 더 넓은 범위어야 함.
    7. 예외처리는 같은 예외이거나 더 구체적(하위)인 예외를 처리해야 함.
    
    <aside>
    💡 @Override : 어노테이션 (오버라이딩 시 실수의 여지를 줄여주고 가독성을 높여준다.)
    
    </aside>
    
- 다형성(Polymorphism)
    - 다형성의 개념과 특징
        - 다형성은 송신자와 수신자 간의 객체 타입에 대한 결합도를 메시지에 대한 결합도를 낮춤으로서 달성된다.
        - 상속 개념 안에서 자식 클래스로 갈 수록 부를 수 있는 자료형이 많아진다. 
        C→B→A 의 상속구조에서, C는 C뿐만 아니라 B와 A로도 불릴 수 있다는 것이 다형성의 핵심.
        - 즉, 하나의 인스턴스가 여러가지 타입을 가질 수 있는 것을 의미한다.
        - 여러 타입의 객체를 하나의 타입으로 관리할 수 있으므로 결합성을 낮춰 유지보수성을 증가시키고, 확장성이 좋은 코드를 작성 가능해짐으로 생산성이 증가된다.
        
        ```java
        Animal a1 = new Rabbit();
        		Animal a2 = new Tiger();
        		
        //		Rabbit r = new Animal();
        //		Rabbit t = new Animal();
        ```
        
        - Rabbit extends Animal 일 때. 위가 되지만 아래가 되지 않는 이유 : 
        Animal에 없는 b라는 멤버를 제외하면 Rabbit이 Animal이 될 수 있지만 (업 캐스팅)
        Animal에  없는 b라는 멤버를 생성하여 Animal이 Rabbit이 될 수는 없다. (다운 캐스팅)
        - 단, Animal로 레퍼런스 변수를 설정했을 때, Animal에 없는 Rabbit의 b 멤버를 실행할 수는 없다. 이는 정적 바인딩으로 판단하기 때문이다.
        - 이와 같은 문제를 형변환을 통해 출력은 할 수 있지만, instanceof를 활용하지 않은 형변환은 런타임 에러가 발생할 수 있기에 사용해선 안 된다.  (컴파일 중에는 정적 바인딩이, 런타임 중에 동적 바인딩이 되기 때문이다.)
        - 그렇기에 등장하는 것이 instanceof
        
        ```java
        for(int i = 0; i < animals.length; i++) {
        			if(animals[i] instanceof Rabbit) {
        				((Rabbit)animals[i]).jump();
        			} else if (animals[i] instanceof Tiger ) {
        				((Tiger)animals[i]).bite();
        			} else {
        				System.out.println("이 인스턴스는 호랑이나 토끼가 아닙니다.");
        			}
        		}
        ```
        
        - 위와 같은 for문을 활용, animal 레퍼런스 변수가 Rabbit에 속해있는지, Tiger에 속해있는지 판별하여 서로 다른 결과를 출력하게 할 수 있다.
    - 동적 바인딩과 정적 바인딩
        - 바인딩은 묶여있다는 의미를 가지고 있다.
        - 따라서 동적 바인딩은 컴파일 당시에는 해당 타입의 메소드와 연결되어(묶여) 있다가 (정적 바인딩) 런타임 당시 (자바가 판단하여)  실제 객체가 가진 오버라이딩 된 메소드로 바인딩이 바뀌어 동작하는 것을 의미한다.

# 14. 추상클래스와 인터페이스(Interface)

- 추상화
    - 어떤 양상, 세부 사항, 구조를 좀 더 명확하게 이해하기 위해 특정 절차나 물체를 의도적으로 생략하거나 감춤으로써 복잡도를 극복하는 방법.
    - 추상화는 다음의 두 가지 차원에 의해 이뤄진다.
    1. 구체적인 사물들 간의 공통점은 취하고 차이점은 버리는 일반화를 통해 단순하게 만드는 것.
    2. 중요한 부분을 강조하기 위해 불필요한 세부 사항을 제거함으로써 단순하게 만드는 것. 
- 추상클래스의 개념과 사용 목적
    - 추상메소드(불안정한 메소드)를 0개 이상 포함하는 클래스
    단, 추상메소드가 1개라도 있으면 클래스 역시 추상클래스가 되어야 한다.
    - 추상클래스로는 인스턴스를 생성할 수 없다.
    - 추상클래스로 인스턴스를 생성하려면 추상클래스를 상속받은 하위 클래스를 이용해서 인스턴스를 생성해야 한다.
    - 추상클래스의 추상메소드는 오버라이딩에 대한 강제성이 부여된다. (반드시 오버라이딩을 해야 한다.)
    이는 필수 기능을 정의하여 강제성을 부여함으로서 개발 시 일관된 인터페이스를 제공할 수 있게 함이다.
    - 부모클래스가 abstract method가 있는 abstract class라면 자식 클래스는 abstract class가 되거나, abstract method를 정상적인 method로 ({}사용) 오버라이드 해야 한다.
    - 추상 클래스는 결국 다형성 적용을 위해 부모 클래스를 만들 때 자주 쓰인다. 또한, 추상 메소드를 통해 자식 클래스에게 규약을 줄 때 사용된다.
- 인터페이스의 개념과 사용 목적
    - 추상메소드와 상수 필드만 가질 수 있는 클래스의 변형체.
    - 추상클래스와 비슷하게 필요한 기능을 공통화 해서 강제성을 부여하는게 목적이다. (표준화)
    - 자바의 단일 상속의 단점을 극복했다. (다중 상속)
    - 인터페이스 영역은 인터페이스 { } 블럭 내부에서만 선언 가능하며, 
    public static final
    의 형식을 가진다. (단, 생략 가능하다. 이는 이러한 형태로만 가능한 공간이기 때문이다.)
    - 또한, 인터페이스 영역의 메소드는 추상 메소드만 가능하다. 따라서, 생략이 가능하지만 묵시적으로 
    public abstract
    의 형식을 가진다.
    
    <aside>
    💡 예외! :
    static method는 구현부까지 사용 가능하다.
    default 키워드를 사용하면 non-static 메소드도 구현부까지 작성 가능하다. (JDK 1.8 이상)
    
    </aside>
    
    ![9.png](JAVA_교육/9%201.png)
    
    - 다중상속이 불가능하기에, 이미 상속중인 class 2개를 묶는 부모 class를 하나 새로 만들어낼 수는 없다.
    - 그럴 때 사용할 수 있는 것이 interface.
    - interface는 상속 중인 자녀 class 둘을 묶어서 하나의 interface를 부모로 삼을 수 있다.
    - 이처럼 타입만을 적용하고 싶을 때 사용하는게 Marker interface
    - Interface의 목적은 규약을 주는 것과 유지보수성을 위함이다. 
    이 때, 유지보수성은 interpace를 통해 은닉 기술을 발휘함에 있다.
    - implements 로 구현한다.
    
    <aside>
    💡 인퍼테이스는 상속받을 수 있는 수에 제한이 없다. 또한, 인터페이스의 상속과는 별개로 클래스 상속 역시 가능하다.
    따라서, 클래스 하나를 extends로 상속 받고, implements 를 통해 다수의 인스턴스를 상속 가능하다.
    또한, 인터페이스는 인터페이스를 상속받을 수 없기에, 인터페이스 끼리는 extends를 통한 상속이 가능하다. 단, 인터페이스는 class와 달리 다중상속이 가능하다.
    
    </aside>
    

![10.png](JAVA_교육/10%202.png)

- 단, 인터페이스의 다중 상속은 인터페이스 사이에서만 가능.

# 15. 디자인 패턴

- 디자인 패턴이란 : 객체지향 프로그래밍을 순수히 만들면 생기는 다양한 문제점들을 해결하고, 효율적으로 관리하기 위해 만든 패턴. 싱글톤 패턴은 가장 기본적인 디자인 패턴.
- 싱글톤 패턴(singleton pattern)
    - 클래스의 인스턴스 사용시 메모리 공간에 있는 하나의 인스턴스를 공유해서 사용하는 디자인 패턴
    - new 연산자를 통해 동일한 인스턴스를 계속 생성하는게 아닌, 한 번 생성한 인스턴스를 공유해서 계속 사용하도록 할 수 있다. 즉, 최초 한번만 메모리에 할당되고 그 메모리에 인스턴스를 만들어, 하나의 인스턴스를 공유해서 사용하며 메모리 낭비를 방지할 수 있다.
    - 프로그램이 start 되자마자 new연산자를 사용하지 않아도 자동으로 인스턴스를 생성, 이후 해당 인스턴스가 호출될 때마다 계속 불러오는 것이 EagerSingleton
    - 프로그램이 start될 때는 생성되지 않고, 프로그램이 최초 호출 될 때 new 연산자를 통해 인스턴스를 생성, 이후 해당 인스턴스를 호출할 때마다 계속 불러오는 것이 LazySingleton.

# 16. API

- API 정의
    - Application Programming Interface를 줄인 말로, 응용프로그램에서 사용할 수 있도록 운영체제나 프로그래밍 언어가 제공하는 기능을 제어할 수 있도록 만든 인터페이스
    - Application : 운영체제 위에서 돌아가는 모든 프로세스
    - Programming Interface : 프로그램을 운영하기 위해 필요한 모든 것.
    - 동일 비교 : 주소가 동일한 인스턴스인지 비교
    - 동등 비교 : 주소가 다르더라도 필드 값이 동일한 인스턴스인지 비교
- Object API
    - Java의 모든 클래스는 Object 클래스의 후손이다.
    - Java.lang 패키지 안에 존재한다.
    - toString()
        - 인스턴스 생성 시 사용한 full class name과 @ 그리고 16진수 해쉬코드가 문자열로 반환.
        배열 안의 내용을 출력하고 싶을 때는 Override 필요
    - equals()
        - 매개변수로 전달 받은 인스턴스와 == 연산하여 true 또는 false를 반환, 동일 인스턴스인지를 비교한다.
        - 단, object에 있는 equals()를 오버라이딩 하여, 원하는 기준에 맞춰 일치하는지 동등 비교할 수 있다.
        - 오버라이딩 시 해쉬코드의 동일 비교도 오버라이딩 하는데, 이후로는 기준(필드 값)에 맞으면 동일한 해쉬코드를 갖게 되며, 해쉬코드 동등 비교가 가능해진다.
        이를 해쉬알고리즘이라 하며. 더 빠른 비교를 위해 분류한다고 생각하면 된다.
    - hashCode()
        - 인스턴스의 주소값을 변환하여 생성한 인스턴스의 고유 값을 반환한다.
- String API
    - Sting의 특성
        
        ![제목 없음.png](JAVA_교육/%EC%A0%9C%EB%AA%A9_%EC%97%86%EC%9D%8C%201.png)
        
        - 상수풀(constart pool) : equals와 hasCode를 사용한다. 두 method를 이용하여 생성하려는 인스턴스와 동등 개체가 있는지 확인하여, 상수풀에 동등 개체가 있다면 새로 생성하지 않는다. 이후, 동등 개체의 주소값이 새로운 레퍼런스 변수로 대입된다.
        - 그렇기에 “” 리터럴 형태는 == 가 true, 
        new String(”문자열”) 형태는 ==가 false가 된다.
        - String은 문자열이 할당된 공간이 문자열이 수정될 때마다 새로이 할당된다는 특성을 가지고 있다. 이 같은 특성을 불변이라고 한다.
        - String은 equals()와 hashCode() 메소드를 둘 다 오버라이딩 되어 있다.
        - 기본 자료형이 stack 영역에 저장되는 것과 달리, String은 참조 자료형이기에 heap 영역에 저장된다. 단, new 연산자를 사용하지 않을 경우, heap 영역에 있는 상수풀(constart pool)에 저장된다. 이 때, 동일한 내용이 있을 경우, 다른 변수명을 사용하더라도 새로운 인스턴스를 생성하는 것이 아닌, 기존에 있던 내용과 동등비교하여 동등하다면 기존에 있던 인스턴스의 주소값을 참조하게 한다.
        단, new 연산자는 상수풀이 아닌 heap영역에 저장되며, 동등한 내용이라고 해도 인스턴스를 새로이 만든다.
    - String 관련 APi
        - 문자열 비교
            - compareTo() : 인자로 전달된 문자열과 사전 순으로 비교, 두 문자열이 같다면 0을 반환, 인자로 전달된 문자열보다 작으면 음수, 크면 양수를 반환한다. 
            단, 대소문자를 구분하여 비교한다.
            
            ```java
            String str2 = "java";
            String str3 = "java";
            String str4 = "JAVA";
            String str5 = "oralce";
            
            System.out.println("compare To() : " + (str2.compareTo(str3)));
            System.out.println("compare To() : " + (str2.compareTo(str4)));
            System.out.println("compare To() : " + (str4.compareTo(str2)));
            ```
            
            - 위의 코드에서, 출력값은 0, 32, -32 가 된다. (유니코드 비교)
            
            <aside>
            💡 compareToIgnoreCase()
            대소문자 구분 없이 비교한다.
            
            </aside>
            
        - 문자열 변경
            - concat() : 문자열에 인자로 전달된 문자열을 합쳐 새로운 문자열을 반환한다. 원본에는 영향을 주지 않는다.
            - toLowerCase() : 문자열의 모든 문자를 소문자로 변환시킨다.
            - toUpperCase() : 모든 문자를 대문자로 변환시킨다.
            둘 다 원본에는 영향을 주지 않는다.
            - trim() : 문자열의 앞 뒤에 위치한 공백을 제거한 문자열을 반환한다.
            - substring() : 문자열의 일부분을 잘라내어 새로운 문자열을 반환한다.
            원본에는 영향을 주지 않는다.
            
            <aside>
            💡 단, 마지막 문자 -1이 적용된다. 
            
            String javaoracle = "javaoracle";
            System.out.println(javaoracle.substring(4,10));
            에서, (4,9) 로 하면 oracle이 아닌 oracl이 출력된다.
            또한, (4)로 입력시 마지막까지 알아서 잘라준다.
            
            </aside>
            
            - replace() : 문자열에서 대체할 문자열로 기존 문자열을 변경해서 반환한다.
            원본에는 영향을 주지 않는다.
        - 문자열 출력
            - charAt() : 해당 문자열의 특정 인덱스에 해당하는 문자를 char형으로 반환
            - length() : 문자열의 길이를 정수형으로 반환한다.
        - 문자열 검색
            - indexOf() : 문자열에서 특정 문자를 탐색하여 처음 일치하는 인덱스 위치를 정수형으로 반환한다.  단, 일치하는 문자가 없을 때는 -1을 반환한다.
            
            <aside>
            💡 lastIndexOf() : 문자열에서 특정 문자를 뒤에서 탐색한다. 그 외에는 위와 동일
            
            </aside>
            
            - isEmpty(): 문자열의 길이가 0이면 true를 반환, 아니면 flase를 반환
            길이가 0인 문자열은 null과는 다르다.
        - 문자열 배열
            - split() : ()안의 문자열 값을 기준으로 구분하여 배열화한다.
                
                ```java
                String emp2 = "200/유관순//총무부";
                String[] empArr2 = emp2.split("/");
                System.out.println(Arrays.toString(empArr2));
                
                의 출력값
                [200, 유관순, , 총무부]
                이 된다.
                ```
                
                - split 메소드 중에 매개변수가 하나인 것은 마지막 구분자 이후 아무 값이 없으면 한 칸으로 인지하지 않게 되며, 구분자가 중간에 있는 것들과 달리 배열의 크기도 한 칸이 작게 된다.
            - StringTokenizer() : ()안에 정의한 토큰이 있으면 기존의 커서의 값을 반환하고, 커서를 다음 칸으로 옮긴다.
                
                ```java
                StringTokenizer st2 = new StringTokenizer(emp2, "/");
                
                while(st1.hasMoreTokens()) {
                			System.out.println("st1: " + st1.nextToken());
                		}
                
                의 출력값
                st1: 100
                st1: 홍길동
                st1: 서울
                st1: 영업부
                이 된다.
                ```
                
        - Escape
            - \n : 문자열 안에 엔터 효과
            - \t : 문자열 안에 탭 효과
            - \’’ : 문자열 안에 ‘’ 삽입
            - \\ : 문자열 안에 “”삽입 (”안녕하세요 저는 \” 다자바\” 입니다.”)
            - \\\\: 문자열 안에 \ 삽입 : (”안녕하세요 저는 \\못자바\\입니다.”)
        
        <aside>
        💡 메소드 체이닝 : 메소드 종료 후 다른 메소드를 연이어 사용할 수 있다.
        
        </aside>
        
    - StringBuilder와 StringBuffer
        - StringBuilder : String과 달리 가변 특징을 가지고 있다. 문자열에 .append() 메소드를 이용하여 합치는 경우, 기본 인스턴스를 수정하기 때문에 새로운 인스턴스를 생성하지 않는다. 따라서 잦은 문자열 변경이 일어나는 경우 String보다 성능이 좋다. 스레드 동기화 기능이 제공되지 않는다. 속도가 빠르고 안정성이 낮다.
        - StringBuffer : 메소드 자체는 StringBuilder와 동일하다 단, 스레드 동기화 기능이 제공된다. 속도가 느리고 안정성이 높다.
        - StringBuilder와 StringBuffer에서 자주 사용되는 메소드
            - capacity() : 현재 버퍼의 크기(용량)을 정수형으로 반환하는 메소드
            원본에 영향을 끼친다.
            - append() : 인자로 전달된 값을 문자열로 반환 후 기존 문자열의 마지막에 추가하는 메소드. 기존 용량을 초과하는 경우 (기존 용량 +1) *2를 하여 용량을 확장시킨다.
            원본에 영향을 끼친다.
            - delete() : 시작 인덱스와 종료 인덱스를 이용해서 문자열에서 원하는 부분의 문자열을 제거한다.
            원본에 영향을 끼친다.
            - deleteCharAt() : 문자열 인덱스를 이용해서 원하는 문자 하나를 제거한다.
            원본에 영향을 끼친다.
            - insert() : 인자로 전달된 값을 문자열로 반환 후 지정한 인덱스 위치에 추가한다.
            원본에 영향을 끼친다.
            - reverse() : 문자열 인덱스 순번을 역순으로 재배열한다.
            원본에 영향을 미친다.
        - 이 둘의 비교를 통해 최적화의 개념을 설명할 수 있다.
        - 스레드 동기화는 게임 대기열을 생각하자. 스레드 동기화 기능이 제공되면 대기열이 있고, 제공되지 않으면 없는 것. 전자는 느려도 서버는 터지지 않는다. 후자는 빨라도 서버가 터질 수 있다.
- Wrapper class
    - Wrapper class 정의와 선언
        - 상황에 따라 기본 타입의 데이터를 인스턴스화 해야 하는 경우들이 발생한다.
        - 이 때, 기본 타입의 데이터를 먼저 인스턴스로 변환 후 사용해야 하는데, 
        8가지에 해당하는 기본 타입의 데이터를 인스턴스화 할 수 있도록 하는
        클래스를 래퍼 클래스(Wrapper Class)라고 한다.
        
        ```java
           	 * byte			----->			Byte
        		 * short		boxing			Short
        		 * int									Integer		(v)
        		 * long									Long
        		 * float								Float
        		 * double		<-----			Double
        		 * char			unboxing		Character	(v)
        		 * boolean							Boolean
        
        int와 char 자료형은 대문자가 아닌 풀네임으로 구분되는 점에 유의.
        ```
        
    - Boxing과 UnBoxing
        - 기본 타입을 래퍼클래스의 인스턴스로 인스턴스화 하는 것을 박싱
        래퍼클래스 타입의 인스턴스를 기본 타입으로 변경하는 것을 언박싱이라고 한다.  그 방식은 아래와 같다.
        
        ```java
        Integer boxingNumber1 = Integer.valueOf(intValut);  // 박싱
        int unBoxingNumber1 = boxingNumber1.intValue();     // 언박싱
         
        ```
        
        - 단, 위와 같은 방식보다는 오토 박싱과 오토 언박싱으로, 자바 컴파일러가 자동으로 처리해주는 방식을 더 자주 사용한다. 그 방식은 아래와 같다.
        
        ```java
        Integer boxingNumber1 = intValut;                  // 박싱
        int UnBoxingNumber1 = boxingNumber1;               // 언박싱
        ```
        
    - parsing
        - 문자열 값을 기본 자료형 값으로 변경하는 것이 passing
        
        ```java
        int i = Integer.parseInt("4");
        long l = Long.parseLong("8");
        float f = Float.parseFloat("4.0f");
        
        ```
        
        - 위와 같은 방식으로도 사용할 수는 있으나,
        
        ```java
        int i = Integer.valueOf("4");
        long l - Long.valueOf('*");
        float f = Float.valueOf("4.0f");
        ```
        
        - 위와 같은 방식으로 .valueOf를 사용하는 편이 권장된다.
        - 마찬가지로, 기본 자료형 값을 문자열로 변경할 수도 있는데,
        
        ```java
        String i = Integer.valueOf(4).toString();
        String l = Long.valueOf(8L).toString();
        String f = Float.valueOf(4.0f).toStirng();
        ```
        
        - 위처럼 valueOf를 사용하거나
        
        ```java
        String str = 123 + "";
        ```
        
        - 위의 방식처럼 문자열 합치기를 응용한 방식으로 더 자주 사용된다.
- Date
    
    <aside>
    💡 사용자가 아닌, 서버의 표준 시간을 기준으로 한다.
    
    </aside>
    
    - Date
        - Date 클래스의 경우, 생성자를 비롯해서 대부분의 메소드가 Deprecated 되어 있다.
        - Date는 import의 과정이 2가지 존재한다. 
        java.sql.Date
        java.util.Date
        의 두 가지.
        - 한 클래스에서 두 개의 타입을 전부 사용하게 되면 omport를 하더라도 사용하는 타입이 어느 패키지에 있는 Date클래스인지에 대한 모호성이 발생한다.
        - 따라서, iimport를 해도 풀 클래스명을 작성해주는 편이 좋다.
        
        ```java
        java.util.date를 import 했을 경우.
        java.sql.Date d = new java.sql.Date();
        date d = new Date();
        ```
        
        - 기본 생성자로 인스턴스를 생성할 경우, 컴퓨터의 운영체제 날짜/시간 정보를 이용해서 인스턴스를 만들게 된다. (시스템시간) 그 방식은 아래와 같다.
        
        ```java
        Date today = new Date();
        
        System.out.println(today.toString());
        // toString() 메소드가 오버라이딩 되어 있어 필드값 출력이 가능하다.
        ```
        
        - Date(long date) 생성자를 활용한 방식도 있는데, 이는 그리니치 표준시 기준, 1970년 1월 1일 0시 0분 0초 이후 지난 시간을 millisecond로 계산해서 long 타입으로 변환하는 메소드이다.  그 방식은 아래와 같다.
        
        ```java
        System.out.println(today.getTime());
        을 통해 지난 시간을 확인 후 
        
        Date time = new Date();
        System.out.println(tim);
        ```
        
        - () 안에 지난 시간을 직접 입력하여 사용한다.
        - 단, 이와 같은 방식은 Calender 클래스가 있기에 사용되지 않는다. 어디까지나 참조 개념으로 받아들이자.
    - Calendar (GregorianCalendar)
        
        <aside>
        💡 Calendar 클래스는 abstract 클래스이기에, 인스턴스 생성이 불가능하다.
        
        </aside>
        
        - 따라서, 2가지 방법이 사용되는데, 각각은 아래와 같다.
        
        <aside>
        💡 Calendar calendar = Calendar.getInstance();
        System.out.println(calendar);
        
        </aside>
        
        - 위와 같은 방식으로, 싱글톤 패턴을 적용하여 getInstance() 메소드를 통해 출력하거나
        
        ```java
        Calendar gregorianCalendar = new GregorianCalendar();
        System.out.println(gregorianCalendar);
        ```
        
        - 위와 같은 방식으로, Calendar 클래스의 상속 클래스인 GregorianCalendar를 다향성을 활용하여 인스턴스화 한 뒤, 출력한다.
        - 시스템 시간이 아닌 원하는 시간을 출력하고 싶은 경우에는 각각의 변수를 지정한 뒤, 인스턴스화 하는 과정을 거친다. 그 방식은 아래와 같다.
        
        ```java
        int year = 2014;
        		int month = 9 -1;
        		int dayOfMonth = 18;
        		int hour = 16;
        		int min = 42;
        		int second = 20;
        		
        		Calendar birthDay =
        				new GregorianCalendar(year, month, dayOfMonth, hour, min, second);
        		System.out.println(birthDay);
        		
        		int birthYear = birthDay.get(GregorianCalendar.YEAR);
        		int birthMonth = birthDay.get(Calendar.MONTH);
        		int birthDayOfMonth = birthDay.get(Calendar.DATE);
        
        		System.out.println(birthYear);
        		System.out.println(birthMonth + 1);				
        		System.out.println(birthDayOfMonth);
        ```
        
        - 각각의 int값으로 년,월,일,시,분,초를 지정한 뒤. GregorianCalendar를 이용, 인스턴스를 생성한 뒤. 변수값을 지정한 뒤 출력하는 방식을 거친다.
        
        <aside>
        💡 단, 시스템은 월을 1~12월이 아닌, 0~11월로 계산하기에, get을 통해 월을 빼낼 경우에는 변수 지정 시에 -1을. 출력시에 +1을 붙여서 사용해야 한다.
        
        </aside>
        
    - simpleDateFormat
        - 위의 Date와 Calendar (GregorianCalendar)를 활용, 출력의 형식을 지정할 수 있다. 그 방식은 아래와 같다.
        
        ```java
        SimpleDateFormat sdf = new SimpleDateFormat("a yyyy-MM-dd hh:mm:ss E요일 완전 조아");
        		String formatDate = sdf.format(new Date(birthDay.getTimeInMillis()));
        		System.out.println(formatDate);
        
        // 출력값 : 오후 2014-09-18 04:42:20 목요일 완전 조아
        ```
        
        - 위와 같은 방식으로, () 안에 원하는 형식을 지정함으로서 간결하게 출력이 가능하다.
        - 단, simpleDateFormat 의 경우 GregorianCalendar 인스턴스를 가지고 있지 않기에, GregorianCalendar를 Date형으로 변환하는 과정이 필요하다.
    
    <aside>
    💡 Date → (상수필드와 타임 존 추가) Calendar → (윤달의 개념 추가) → GregorianCalendar
    
    </aside>
    
- 그 밖의 JAVA API
    - Math
        - java.lang.Math에서 제공하는 메소드 (타인이 만든 메소드를 가져다 사용하는 메소드)
        - java.lang에서 제공하는 메소드는 클래스명을 적거나 import를 삽입하지 않아도 가능하다.
        - Math.abs (절대값)
        Math.min(두 값 중 최솟값)
        Math.max(두 값 중 최대값)
        Math.PI(원주율)
        Math.random(0-9.99999... 사이의 난수 생성)
        - Random  Varname1= new Random();
        (자료형) Varname 2= random.nextInt(Number1) +Number2;
        Number1 : 구하려는 난수의 갯수 (큰 값 - 빼기 + 1)
        Number2 : 구하려는 난수의 최소값
    - Scanner
        
        ```java
        Scanner Varname = new Scanner(System.in);
        
        System.out.print("이름을 입력하세요: ");
        		String name = sc.nextLine();
        		System.out.println("입력하신 이름은 " + name + "입니다.");
        ```
        
        - Scanner는 java.long Method가 아니기에 클래스 디렉토리를 적거나, import 과정이 필요하다.
        - Scanner는 non static Method이기에 new 연산자를 통한 정의가 먼저 필요하다.
        단, 다른 Method와 달리 
        Scanner Varname = new Scanner(System.in);
        으로 ()안에 System.in을 입력해야 한다.
        (System.in)은 시스템으로부터 입력을 받는다는 의미다.
        - 이후
        자료형 Varname2 = Varname.API(); 형식으로 사용한다.
        → String name = sc.nextLine();
            - nextLIne : String을 반환하는 메소드.
            
        
        <aside>
        💡 단, 캐릭터 형은
        char ch = sc.nextLine().charAt(0);
        형태로 구현한다.
        
        </aside>
        
        - nextLine을 통해 String을 반환받은 뒤, CharAt(0)을 통해 첫 번째 문자를 잘라 출력한다.
        두 번째 문자는 charAt()의 가로값 안의 숫자를 3으로 변경함으로서 조정 가능하다.
        단, 그럴 경우 ()안의 값 이하의 문자를 사용자가 입력했을 경우 에러가 발생한다.
        - 컴퓨터언어는 기본적으로 엔터를 입력값으로 받지 않는다.
        다만 nextLine()은 버퍼에 남아있는 엔터도 입력값으로 받을 수 있다.
        따라서 nextLine()이 2번 이상 반복되었을 때, 두 번째 nextLine은 ()안의 입력값으로 이전에 입력 해 둔 버퍼에 남아있는 엔터값을 받게 된다.
        이 문제를 해결하기 위해선 
        sc.nextLine();을 통해 기존에 남아있던 버퍼를 해결 한 뒤, 다시 사용해야 한다.
        단. nextLine이 아닌 메소드들로 입력을 받다가 nextLine이 나올 때 추가할 것.
        
        ![4.png](JAVA_교육/4%201.png)
        
        ![5.png](JAVA_교육/5%201.png)
        
        - 띄어쓰기가 필요하면 nextLine()을, 사용하지 않으면 next()를 사용한다.

# 17. 예외

![1.png](JAVA_교육/1%202.png)

<aside>
💡 시스템 상에서 프로그램에 문제가 발생하는 것은 오류와 예외로 구분할 수 있다.
오류(Error) : 시스템 상에서 프로그램에 심각한 문제가 발생하여 실행 중인 프로그램이 종료되는 것. 
예외(Exception) : 오류와 마찬가지로 비정상적으로 종료시키지만, 미리 예측하고 처리할 수 있는  미약한 오류

</aside>

<aside>
💡 finally : 예외 처리 구문과 상관없이 반드시 수행해야 하는 경우 작성한다. 보통은 외부 데이터를 주고받는 통로가 생겼을 때, 사용한 자원을 반납할 목적으로 사용한다.

</aside>

- 예외 처리 방식
    - Java의 오류는 다음의 순서를 따른다. 오류가 발생할 문제(Exception)를 thorws를 통해 상위 클래스에 위임한 후, try-catch로 예외 상황을 처리하는 것. 
    main method 역시 thorws를 통해, JVM에게 던질 수 있다.
    단, 이 경우 오류가 그대로 발생하기에 의미는 없다.
    
    <aside>
    💡 throws로 던져진 exception class는 실제로 예외사항(오류)이 발생하는지의 여부에 상관 없이, try catch를 통한 예외처리가 진행되어야 한다.
    
    </aside>
    
- 예외의 종류
    - Exception
        - RuntimeException
            - ArithmeticException : 어떤 숫자를 0으로 나누면 발생하는 예외 클래스
            단, int형과는 달리, double형은 ArithmeticException이 아닌 infinity가 출력된다. 
            이는, double이 0이 아닌 0에 한 없이 근접한 숫자이기 때문이다.
            - NullPointerException : 객체를 가리키지 않고, 아무것도 참조하지 않거나 null을 참조하는 변수에 엑세스할 때 발생하는 에러.
            - IndexOutOfBoundsException :  인덱스(배열, 문자열, 벡터 등)가 범위를 벗어났을 때 발생하는 에러. 예) 3칸짜리 배열에 5개의 인덱스를 지정했을 때 발생
            
- 예외 클래스 생성
    - 상위 클래스에서 예외클래스 생성(if - throw new-) 후, 하위 클래스 import. 그 후, method에서 throws를 통해 하위클래스를 지정한다.
    
    ```java
    package com.greedy.section02.userexception;
    
    import com.greedy.section02.userexception.exception.MoneyNegativException;
    
    public class ExceptionTest {
    	public void checkEnoughMoney(int price, int money) throws PriceNegativException{
    //		public void checkEnoughMoney(int price, int money) throws Exception {
    		
    		/* 상품 가격이 음수인지 확인하고, 음수인 경우 예외를 발생시킬 것이다. */
    		if(price < 0) {
    			throw new PriceNegativException("상품 가격은 음수일 수 없습니다.");
    		}
    	
    		System.out.println("가진 돈이 충분합니다. 즐거운 쇼핑 되세요~");
    	}
    }
    ```
    
    - 이후, 하위 클래스에서 예외처리할 상황의 기본 생성자 생성. 이후, 예외 메세지 출력을 위한 매개변수 있는 생성자 작성. 이 때, 매개변수 있는 생성자인 String message는 상위 클래스인 ExceptionTest 의 예외처리 문구를 출력할 수 있게 하는 기능을 한다.
    
    ```java
    package com.greedy.section02.userexception.exception;
    
    public class PriceNegativException extends Exception{
    
    	public PriceNegativException() {}
    	
    	public PriceNegativException(String message) {
    		super(message);
    	}
    }
    ```
    
    - 이후, main method에서 try { } catch() { } 를 통해 예외 처리한 상황을 적용한다..
    
    ```java
    try {
    
    			et.checkEnoughMoney(20000, -30000);
    
    		} catch (NotEnouhMoneyException e) {		
    			e.printStackTrace();
    
    		} catch (PriceNegativException | MoneyNegativException e) {
    			System.out.println("입력 값이 음수일 경우 예외 발생!");
    			System.out.println(e.getClass() + " 발생!");		
    			System.out.println(e.getMessage());			
    			
    		} catch (Exception e) {				
    			e.printStackTrace();
    		} finally {
    
    			System.out.println("finally 블럭이 내용이 동작함...");
    		}
    ```
    
    - 매개변수로 예외 클래스를 다수 받을 수 있다.
    - 처리할 예외 상황이 여러 개일 경우, 상위 클래스인 Exception을 통해 다향성을 적용할 수 있다.  단, 상위 클래스로 던질 경우 하위 클래스를 각각 적용할 수 없기에 권장되진 않는다.
    - 이는 try - catch에도 적용되는 사항으로, catch() 의 ()안에 예외처리할 method가 아닌, 상위 클래스 Exception을 넣음으로서 하위 예외 클래스들을 함께 불러올 수 있다.  단, 하위클래스 이전에 상위클래스를 넣을 경우, 하위 클래스는 닿을 수 없기에 컴파일 에러가 발생한다. (Unreachable code)
    
    <aside>
    💡 이 같은 방식을 multi - catch 라고 한다. (JDK 1.7 이후 추가)
    
    </aside>
    
    <aside>
    💡 .printStackTrace(); 을 통해 예외 클래스명, 예외 발생 위치, 예외 메시지 등을 stack 호출 역순으로, 빨간색 글씨를 이용 로그 형태로 출력할 수 있다.
    
    </aside>
    
- try~with~resource
    - close()를 자동으로 호출해 주는 구문. 코드를 간결하게 해 주고, 실수의 여지를 줄여준다.
    
    ```java
    try(BufferedReader in = new BufferedReader(new FileReader("test.dat"))){
    			String s;
    			
    			while((s = in.readLine()) != null) {
    				System.out.println(s);
    			}
    		} catch(FileNotFoundException e) {
    			e.printStackTrace();
    		} catch(IOException e) {
    			e.printStackTrace();
    		}
    ```
    
    - 위와 같은 형식으로 사용된다. try()의 가로 안에서 인스턴스 생성 시 해당 구문이 종료될 때 자동으로 .close가 사용되기에, 신경 쓸 여지가 줄어든다.
- Exception과 Override
    - 예외를 던지는 메소드 역시 기본적으로 오버라이딩이 가능하다.
    - 단, 부모가 던지는 예외보다 상위의 예외 class는 자식 클래스에서 오버라이딩 할 수 없다.

# 18. 입출력

- 입출력과 스트림의 개념
    - 하나의 기준 (RAM, 컴퓨터와 같은 것들)을 두고 들어 오고 나가는 것이 입력과 출력.
    - 정보(resource)를 주고 받을 때의 통로- 입력과 출력 - 를 스트림(stream)이라고 한다.
    - 스트림은 단방향이며 각각의 장치마다 연결할 수 있는 스트림이 존재한다. 즉, 하나의 스트림으로 입출력을 동시에 수행할 수 없다.
    - 스트림은 바이트 기반 스트림과 문자 기반 스트림이 있다.
    바이트 스트림은 데이터를 바이트 단위(8비트)로 처리하며,
    문자 기반 스트림은 데이터를 문자별로 읽고 쓸 수 있다.
    - 프로그램이 도착지면 입력 스트림을, 프로그램이 출발지면 출력 스트림을 사용한다.
- File
    - File class의 파일/디렉토리 생성 및 삭제와 관련된 메소드들이다.
    - 자주 사용되는 method는 아래와 같다.
    - File filename = new File(”file directory”);
    의 형식으로 인스턴스화 가능하다.
    - 실제로 파일을 디렉토리 내에 만들고 싶을 때는 
    boolean varName= fileName.createNewFile();
    의 형식으로 가능하다.
    (한 번 생성 후 삭제전까지 재생성은 되지 않는다.)
    - 생성한 파일의 삭제는 
    boolean varName = fileName.delete();
    
    ![4.png](JAVA_교육/4%202.png)
    
    ![6.png](JAVA_교육/6%202.png)
    
    ![5.png](JAVA_교육/5%202.png)
    
- 스트림의 종류
    - 바이트 기반 스트림
        - Input Stream
            
            ![7.png](JAVA_교육/7%203.png)
            
            - FileInputStream
                - FileInputStream : 시스템의 파일에서 입력 바이트를 얻기 위해 사용된다. 
                FileInputStream Varname = null;
                try{
                Varname = new FileInputStream(”디렉토리 주소”);
                } catch
                의 형식으로 사용 가능하다.
                - read() 와 같은 내부 method를 통해 파일에 기록된 값을 순차적으로 읽어오고, 더 이상 읽어올 데이터가 없을 경우 -1을 반환할 수 있다.
                - 단, read() method의 경우 한글과 같은 문자열은 글자당 3byte로 이루어져있어, 읽어올 경우 글씨가 깨져서 출력된다.
                - byte[] varName = new byte[(int)fileLength];
                와 같은 내부 method를 이용, byte 배열을 만드는 방법도 있다.
            - BufferedInputStream
                - 바이트 기반 입력 스트림에 연결되어 버퍼를 제공해주는 보조 스트림.
            - DataInputStream
                - 데이터를 자료형 별로 처리하는 기능을 추가한 보조스트림.
                단, 저장할 때의 자료형 단위와 순서 그대로 읽어와야 한다.
                - 기본 자료형인 boolean, char, short, int, long, float, double을 입력할 수 있다.
            - ObjectInputStream
                - 객체 단위로 입력을 하기 위한 스트림.
                - 객체로 역직렬화(바이트 배열을 다시 객체로 복원하는 것) 하는 역할을 한다.
        - Output Stream
            
            ![8.png](JAVA_교육/8%202.png)
            
            - File Output Stream
                - 프로그램의 데이터를 파일로 내보내기 위한 용도의 스트림이며, 1바이트 단위로 데이터를 처리한다.
                - Output Stream의 경우, 대상 파일이 존재하지 않으면 파일을 자동으로 생성해준다. (단, 중간 디렉토리 경로가 잘못 되면 FileNotFoundException이 발생한다.)
                - 두 번째 인자로 true를 전달하면 이어쓰기 가능, 기본값은 덮어쓰기이며, false 역시 덮어쓰기로 이루어진다.
                - .write 를 통해 바이트 단위의 글자를 입력할 수 있으며,
                - input과 마찬가지로 배열 형태로의 입력 역시 가능하다.
            - BufferedOutputStream
                - 바이트 기반 출력 스트림에 연결되어 버퍼를 제공해주는 보조 스트림
            - DataOutputStream
                - 데이터를 자료형 별로 처리하는 기능을 추가한 보조스트림.
                - 기본 자료형인 boolean, char, short, int, long, float, double을 출력할 수 있다.
            - ObjectOutputStream
                - 객체 단위로 출력을 하기 위한 스트림.
                - 객체를 직렬화(객체를 바이트 배열로 만드는 것) 하는 역할을 한다.
    - 문자 기반 스트림
        - Reader
            
            ![9.png](JAVA_교육/9%202.png)
            
            - File Reader
                - FileInputStream과 사용하는 방법은 거의 동일하다.
                - 단, byte단위가 아닌 character단위 (인코딩 방식에 맞춰서 정해진다)로 인지하고 읽어들인다는 부분이 차이점.
                - 따라서 한글과 같은 문자열 역시 읽어올 수 있다.
                
                <aside>
                💡 char[] cArr = new char[(int)new File(디렉토리주소).length()/3];
                단, 배열을 만들고 읽어올 경우, 문자열 하나에 3바이트가 지정되므로, 배열의 인덱스 길이를 /3 해줘야 빈 배열이 생기지 않는다.
                
                </aside>
                
            - InputStreamReader
                - 바이트 기반 입력 스트림에 연결되어 문자 입력 스트림인 Reader로 반환하는 보조 스트림.
            - BufferedReader
                - 문자 기반 입력 스트림에 연결되어 버퍼를 제공해주는 보조 스트림.
                - 버퍼 스트림의 경우, 단독으로 사용되지 않고 기본 스트림에 연결되어 사용되는 보조 스트림이기에 다른 형식을 가진다.
                - BufferedReader Varname = null;
                try{
                Varname = new BufferedReader(new FileReader(””디렉토리 주소));
                의 형식으로 사용된다. 즉, new 연산자를 사용, 인스턴스 화 된 기본 스트림에 인스턴스화 된 버퍼 스트림을 붙이는 형식.
                - 단, 버퍼의 경우 버퍼가 가득 차면 자동으로 내보내지만, 버퍼가 가득 차기 전에는 flush()를 통해 강제로 내보내기가 필요하다. 단, close() 안에 flush()가 포함되어 있기에, close()를 사용했다면 굳이 사용하지 않아도 내보내기가 가능하다.
                    
                    <aside>
                    💡 BufferedReader는 readLine(); 이라는 유용한 메소드를 제공한다. 해당 메소드는 키보드에서 입력한 내용 및 파일 내용을 라인 단위(엔터)로 읽을 수 있다.
                    
                    </aside>
                    
        - Writer
            
            ![10.png](JAVA_교육/10%203.png)
            
            - File Wirter
                - 프로그램의 데이터를 파일로 내보내기 위한 용도의 스트림으로, 바이트가 아닌 문자열 단위로 이루어진다는 점이 다를 뿐, 문법이나 사용방법은 File Output Stream과 거의 유사하다.
            - OutputStreamWriter
                - 바이트 기반 출력 스트림에 연결되어 문자 출력 스트림인 Writer로 변환하느 ㄴ보조 스트림.
            - BufferedWriter
                - 문자 기반 출력 스트림에 연결되어 버퍼를 제공해주는 보조 스트림.
                - 기본 형식은 BufferedReader와 큰 차이가 없다. 전자가 읽기라면 후자가 쓰기인 차이 정도.
            - PrintWriter(PrintStream)
                - System.out이 바로 PrintStream
                - .print() method 제공. println으로 출력하는 기능이 있다.
                - 연결할 출력 스트림을 생성자의 매개값으로 받는다.
    - 보조 스트림
        - filter와 같은 역할을 한다. 스트림의 기능을 향상시키거나 새로운 기능을 추가, 기존의 기능을 변환하기 위해서 사용한다.
        - 보조 스트림은 단독으로 사용할 수 없다. (자체적으로 입출력이 불가능하다.)
        - 외부 데이터에 직접 연결되는 기본 스트림과 달리, 필터 스트림은 기본 스트림에 추가로 사용할 수 있는 스트림이며, 하나의 기본 스트림에 여러 개의 필터 스트림이 추가될 수 있다. 즉, 여러 개의 보조 스트림을 하나의 입출력 스트림에 연결하여 사용할 수 있다.
        - buffered는 속도가 빠른 구간에서 데이터를 모은 뒤, 느린 구간을 한꺼번에 옮기는 식의 개념이다.
        - 보조 스트림은 연결할 입출력 기반 스트림을 생성자의 매개변수값으로 받는다.
- 직렬화
    - 스트림은 입출력이 가능한 데이터의 단위가 작다. 따라서 데이터를 파편화 하여 스트림을 오고가는 과정이 필요해지는데, 이때 파편들을 구분하기 위해 꼬리표를 붙이는 과정이 직렬화.
    - implements Serializable 필요. 이 때 나오는 
    private static final long serialVersionUID = 
    는 꼬리표를 붙이는 것. (버전 관리)
    이는 자바빈의 형식 중 하나기도 하다.

<aside>
💡 스트림에서 주고받는 데이터의 크기가 바이트/캐릭터(단, 직렬화를 통해 묶여있을 뿐 지나다니는 데이터의 크기 자체는 바이트) 단위일 뿐, 주고받는 문자열의 크기가 바이트/캐릭터 단위로 제한되는 것은 아니다.

</aside>

<aside>
💡 상대경로 : 내 프로젝트, 현재 위치를 기준으로 경로를 잡는다.
절대경로 : 최상위 디렉토리부터 목적지까지의 절대적인 경로

</aside>

# 19. 네트워크

- 네트워크의 개념
    - 네트워크(Network)는 여러 대의 컴퓨터를 통신 회선으로 연결한 것이다.
    - 서버는 서비스를 제공하는 컴퓨터(혹은 프로그램)
    - 클라이언트는 서비스를 받는 컴퓨터(혹은 프로그램)
    - IP주소는 네트워크 상에서 컴퓨터를 식별하는 번호로, (네트워크 어댑터(랜카드)마다 할당 된다.)
    - 포트는 같은 컴퓨터 내에서 프로그램을 식별하는 번호로, 클라이언트는 서버 연결 요청 시 IP주소와 포트 번호를 알아야 한다.
- TCP, UDP
    - TCP는 데이터를 보내고 받았음을 확인 후 보내는 것.
    
    ![4.png](JAVA_교육/4%203.png)
    
    - 위와 같은 방식으로 이루어진다.
    - 데이터 전송 속도가 느리지만, 정확하고 안정적으로 전달할 수 있는 연결 지향적 프로토콜이다.
    - 반면 UDP는 데이터를 실시간으로 오고 가며 전송하는 것.
    - 데이터 전송 속도가 빠르지만 신뢰성 없는 데이터를 전송하는 비연결 지향적 프로토콜이다.
- 소켓
    - 네트워크에서 실행되는 두 프로그램 간의 양방향 통신 링크의 한 끝 점이다.
- Java에서 Server와 Client로 통신하기.
    
    ```java
    package network;
    
    import java.io.BufferedReader;
    import java.io.IOException;
    import java.io.InputStream;
    import java.io.InputStreamReader;
    import java.io.OutputStream;
    import java.io.PrintWriter;
    import java.net.ServerSocket;
    import java.net.Socket;
    
    public class Server {
    	public static void main(String[] args) throws IOException {
    
    		int port = 8700;
    		
    		ServerSocket server = new ServerSocket(port);
    		
    		while(true) {
    			
    			Socket Client = server.accept();  
    			
    			InputStream input = Client.getInputStream();
    			OutputStream output = Client.getOutputStream();
    			
    			BufferedReader br = new BufferedReader(new InputStreamReader(input));
    			PrintWriter pw = new PrintWriter(output);
    			
    			while(true) {
    				String message = br.readLine();
    				
    				if(!message.equals("exit")) {
    					System.out.println(Client.getInetAddress().getHostAddress() + "가 보낸 메시지 " + message);
    					
    					pw.print("메세지 수신 성공");
    					pw.flush();
    				} else {
    					System.out.println("접속 종료");
    					break;
    				}
    			}
    			
    			pw.close();
    			br.close();
    			Client.close();
    		}
    	}
    
    }
    ```
    
    - Server 기준.
    1. 서버의 포트 번호를 지정한다. 
    2. ServerSocket의 인스턴스를 만들어 레퍼런스 변수 server에 지정한다.
    3. 이후 ServerSocket은 client로부터 연락이 올 때까지 대기한다.
    4. client로부터 접속 요청이 왔을 시, 수락(accept() method) 후 클라이언트에 대한 Socket Object 생성.
    5. 이후 InputStream 과 OutputStream을 통해 클라이언트와 입출력이 가능하게 만들기. 
    6. 이후 BufferedReader와 PrintWirter를 통해 입출력 Stream 성능 개선.
    7. 스트림을 이용해서 클라이언트가 쓴 메시지 수신 및 출력.
    8. 이후 colse를 사용, 통신 종료.
    
    ```java
    package network;
    
    import java.io.BufferedReader;
    import java.io.IOException;
    import java.io.InputStream;
    import java.io.InputStreamReader;
    import java.io.OutputStream;
    import java.io.PrintWriter;
    import java.net.InetAddress;
    import java.net.Socket;
    import java.net.UnknownHostException;
    import java.util.Scanner;
    
    public class Client {
    	public static void main(String[] args) {
    
    		int port = 8700;
    		
    		BufferedReader br = null;
    		PrintWriter pw = null;
    				
    		try {
    			String ServerIp = InetAddress.getLocalHost().getHostAddress();
    			
    			Socket socket = new Socket(ServerIp, port);
    			
    			if(socket !=null) {
    				InputStream input = socket.getInputStream();
    				OutputStream output = socket.getOutputStream();
    				
    				br = new BufferedReader(new InputStreamReader(input));
    				pw = new PrintWriter(output);
    				
    				Scanner sc = new Scanner(System.in);
    			
    				do {
    					System.out.println("대화 입력: ");
    					String message = sc.nextLine();
    					pw.println(message);
    					pw.flush();
    					
    					if("exit".equals(message)) {
    						break;
    					}
    					
    					String recievemessage = br.readLine();
    					System.out.println(recievemessage);
    					
    				}while(true);
    			}
    	
    			pw.close();
    			br.close();
    			socket.close();
    			
    		} catch (UnknownHostException e) {
    			e.printStackTrace();
    		} catch (IOException e) {
    			e.printStackTrace();
    		}
    		
    	}
    }
    ```
    
    - Client 기준.
    1. port 번호 설정. 
    2. Server에서 작성된 메시지를 읽고 출력할 Stream 변수 값 지정.
    3. 서버의 IP주소와 포트번호를 매개변수로 클라이언트용 소켓 객체 생성 
    4. 서버와의 입출력이 가능한 스트림을 작성 (Buffer를 통한 업그레이드)
    5. 스캐너로 입력 창 가능하게 만들기
    6. 스트림을 통해 server에 전달할 말을 쓰고, 서버로부터 오는 메시지를 읽기. 
    7. close를 통해 통신 종료. 

# 20. 제네릭(Generic)

- 제네릭(Generic)의 정의
    - 데이터의 타입을 일반화 한다는 의미를 가지고 있다. 제네릭을 활용하여 타입 변환 및 타입 검사에 들어가는 코드의 생략이 가능하다. (유지 보수)
    - 선언부에서 제네릭의 데이터타입을 무엇으로 설명하느냐에 따라 제네릭의 타입이 변한다.
    - extends 키워드를 사용하여 타입의 제한 역시 가능하다.
    - 제네릭의 이점은 2가지로, 구현의 편리함과 자료형의 안전이 있다.
- 제네릭의 활용
    
    ```java
    public interface Animal { }
    public class Mammal implements Animal{ }
    public class Rabbit extends Mammal {
    
    public void cry() {
    		System.out.println("토끼가 울음소리를 냅니다. 끾끾!");
    	}
    }
    public class Bunny extends Rabbit{ 
    
    @Override
    	public void cry() {
    	System.out.println("바니바니 바니바니 당근 당근");	
    	}
    }
    public class RabbitFarm<T extends Rabbit> { 
    
    public void setAnimal(T animal) {
    		this.animal = animal;
    	}
    }
    
    RabbitFarm<Rabbit> farm4 = new RabbitFarm<>();
    farm4.setAnimal(new Bunny());
    farm4.getAnimal().cry();
    ```
    
    - 위의 코드를 통해 상속구조를 살펴보자.
    - Bunny → Rabbit → Mammal → Animal의 상속구조를 가지고 있다.
    - RabbitFarm → <T extends Rabbit> 의 제네릭 상속구조를 가지고 있다.
    - RabbitFarm의 set 값은 제네릭을 통해 animal로 제한되어있는 상태다.
    - RabbitFarm은 다형성으로 인해<Rabbit>의 인스턴스 생성이 가능하다.
    - Bunny는 Rabbit의 cry method를 Override 한 상태다.
    - farm4 레퍼런스 변수는 다형성으로 RabbitFarm → Rabbit의 상속구조를 가진  변수다.
    - farm4 레퍼런스 변수는 .setAnimal(new Bunny()); 를 통해 Bunny를 동적 바인딩 한 상태다.
    - 따라서, farm4.getAnimal().cry()는 Rabbit이 아닌 Bunny의 Override된 cry method를 실행한다. (동적 바인딩)
    → 구현 은닉
- 와일드카드
    - 제네릭 클래스의 객체를 메소드의 매개변수로 사용 시, 객체의 타입변수를 제한하는 것.
    - 와일드 카드는 anyType(<?>)을 통한 제한 없는 매개변수
    extendsType(<? extends Type>)을 통한 상한제한
    superType(<? super Type>)을 통한 하한 제한이 있다.
    - 제한 없는 매개변수는 제네릭 클래스의 인스턴스라면 다 받을 수 있으며,
    상한 제한은 Type과 Type의 후손을 이용해 생성된 인스턴스만 받을 수 있으며
    하한 제한은 Type과 Type의 부모를 이용해 생성한 인스턴스만 받을 수 있다.
    - 와일드카드는 제네릭에 추가적인 제한을 주는 용도로 이해하자.

# 21. 컬렉션(Collection)

- 컬렉션의 정의
    - 여러 개의 다양한 데이터들을 쉽고 효과적으로 처리할 수 있도록 표준화 된 방법을 제공하는 클래스들의 집합.
    - 데이터를 효율적으로 저장하는 자료구조(저장방식)와 데이터를 처리하는 알고리즘(처리방식)이 미리 구현되어 있다. → 프레임워크(Framework)
    - 컬렉션의 주요 인터페이스 구조는 아래와 같다.
    
    ![2.png](JAVA_교육/2%203.png)
    
    <aside>
    💡 중복값의 비교는 동등비교로 구한다.
    
    </aside>
    
    - Java.util 패키지에 포함.
    - List와 Set은 Iterator를 구동할 수 있고, Map은 구동할 수 없다.
- List
    
    ![3.png](JAVA_교육/3%203.png)
    
    - ArrayList
        - 인스턴스 생성 시 내부적으로 10칸짜리 배열을 생성해서 관리한다. 
        배열의 인덱스가 10칸을 초과할 경우, 2배의 인덱스를 가진 배열을 새로 생성, 기존의 배열을 복사하는 방식으로 자동으로 늘려준다.
        - 다향성을 활용, 상위 레퍼런스로 ArrayList 객체를 만들 수도 있다.
        - ArrayList는 저장 순서가 유지되며, index(순번)이 적용된다.
        - .add()를 통해 배열의 값을 추가할 수 있다.
            - 제네릭을 걸지 않았다면, Object 클래스의 하위 타입 인스턴스를 모두 저장할 수 있다. 단, 이는 자료형의 안전성을 보장하기 위해 권장되지 않는다.
            .add(index, element)를 통해 배열의 특정 인덱스에 추가 역시 가능하다.
        - 내부 배열에 인덱스가 지정되어 있기에 for문으로도 접근 후 확인이 가능하다.
        - .size()를 통해 배열 안 요소의 개수를 측정할 수 있다.
        단, 내부적으로 관리하는 배열의 사이즈는 외부에서 알 필요가 없어 기능이 제공되지 않는다.
        - .set(index, element); 를 통해 배열의 인덱스를 수정할 수 있다.
        - .remove() 를 통해 배열 안 인덱스의 삭제도 가능하다.
        - .isEmpty() 를 통해 배열 안 인덱스가 비어있는지 확인 가능하다. (boolean으로 출력)
        - .clear()를 통해 배열 안 인덱스를 모두 제거할 수 있다.
        - ArrayList는 중복 저장이 가능하다.
        - .sort()를 통해 오름차순으로 string 타입을 정렬 가능하다.
    - LinkedList
        - 인접 참조를 링크해서 체인처럼 관리한다.
        → 특정 인덱스에서 인스턴스를 제거하거나 추가하게 되면 바로 앞/뒤 링크만 변경하면 되기 때문에 인스턴스 삭제와 삽입이 빈번하게 일어나는 곳에서는 ArrayList보다 성능이 뛰어나다.
        
        ```java
        List<String> linkedList = new LinkedList<>();
        ```
        
        - 우선 위와 같은 방식으로 Linkedlist 인스턴스를 생성한 뒤,
        
        ```java
        linkedList.add("apple");
        		linkedList.add("banana");
        		linkedList.add("orange");
        		linkedList.add("mango");
        		linkedList.add("grape");
        ```
        
        - 요소의 추가,삭제,수정은 ArrayList와 동일하다. (출력 역시 동일하다.)
    - Stack
        
        ![4.png](JAVA_교육/4%204.png)
        
        - 리스트 게열 클래스의 Vector 클래스를 상속받아 구현한다.
        - 후입선출(LIFO) 방식의 자료구조이다.
        - Stack은 .add()가 아닌, .push를 통해 배열 안 인덱스를 추가한다.
        - .search()를 통해 배열 안 요소를 찾을 수 있다. 단, 맨 위에서부터 떨어진 거리만큼의 인덱스를 반환한다.
        - .peak() 을 통해 해당 스텍의 가장 마지막에 있는 요소를 반환한다.
        - .pop() 을 통해 가장 마지막에 있는 요소를 반환 후 제거한다.
    - Queue
        
        ![5.png](JAVA_교육/5%203.png)
        
        - 선형 메모리 공간에 데이터를 저장한다.
        - 선입선출(FIFO) 방식의 자료구조이다.
        - 하위 인터페이스로 Deque, BlockingQueue, BlockingDeque, TransferQueue 등 다양하지만, 대부분의 Queue는 LinkedList를 이용한다.
        - 인터페이스기에 Queue가 아닌, LinkedList를 이용해 인스턴스를 생성한다.
            
            ```java
            Queue<String> que = new LinkedList<>();
            ```
            
        - .offer() 를 통해 데이터를 추가한다.
        - .peek() 을 통해 해당 큐의 가장 앞에 있는 요소를 반환한다.
        - .poll() 을 통해 해당 큐의 가장 앞에 있는 요소를 반환하고 제거한다.
    - Compareable & Comparator
        - Compareable
            - implements Comparable을 통해 사용 가능하다.
            - compareTo를 Override하여 사용한다.
            - 제네릭을 사용하지 않았을 경우, 
            return this.price - ((BookDTO)o).price;
            와 같은 식으로 다운캐스팅이 필요하다. (오름차순)
            - 내림차순은 
            return ((BookDTO)o).price - this.price;
            으로 이용한다.
        - Comparator
            - Compareable과 달리, 여러 기준의 정렬을 사용하고 싶을 때 사용한다. 독립된 class에 각각의 기준에 맞는 Comparator 구문을 두고, 불러오는 식으로 사용한다.
            - 제네릭을 사용하였을 경우,
                
                ```java
                public int compare(BookDTO o1, BookDTO o2) {
                		return o1.getPrice() - o2.getPrice();
                	}
                ```
                
                위와 같은 방식으로 사용한다. 
                
            - 이후, bookList.sort(new AscendingPrice()); 와 같은 방식으로 호출하여 사용한다.
    - 출력
        - 4가지 방법이 있다. 각각의 방법은 아래와 같다.
        - ArrayList에 정의 된 toString()을 활용하는 방법
        
        ```java
        System.out.println("bookList: " + bookList);
        ```
        
        - for문을 통한 출력
        
        ```java
        for(int i = 0; i <bookList.size(); i++) {
        		System.out.println(bookList.get(i));
        	}
        ```
        
        - for-each문을 통한 출력
        
        ```java
        for(BookDTO b : bookList) {
        			System.out.println(b);
        		}
        ```
        
        - Iterator를 통한 출력
        
        ```java
        Iterator<BookDTO> iter = bookList.iterator();
        		while(iter.hasNext()) {
        			System.out.println(iter.next());
        		}
        ```
        
- Set
    
    ![6.png](JAVA_교육/6%203.png)
    
    - 개발자가 넣은 저장 순서가 유지되지 않고(단, LinkedHashSet 제외), 중복 인스턴스도 저장하지 못하게 하는 자료구조.
    - null값 역시 중복되지 않고 하나의 null만 저장한다.
    - 모든 자료형은 참조 자료형으로 저장되기에, 중복되는 자료값이 아닌, 중복되는 인스턴스가 있는지가 판단 기준이다.
    - HashSet
        - Set 컬렉션 클래스에서 가장 많이 사용되는 클래스 중 하나이다.
        - 동일 인스턴스 뿐 아니라, 동등 인스턴스도 중복으로 처리하여 저장하지 않는다.
            
            ```java
            HashSet<String> hSet = new HashSet<>();
            Set<String> hSet2 = new HashSet<>();
            ```
            
        - 위와 같은 방식으로 인스턴스를 생성한다. (다형성 적용도 가능하다.)
        - .add() 를 통해 인덱스 값을 추가 가능하다.
        - contains() 를 통해 인자로 전달 된 인스턴스와 동등한 인스턴스가 있다면 true를 반환하게 할 수 있다.
        - .close() 를 통해 인덱스 값을 모두 삭제 가능하다.
        - 출력은 반복문과 iterator()를 통해 가능하지만, 반복문은 권장되지 않는다. iterator()쓰자.
    - LinkedHashSet
        - HashSet이 가지는 기능을 모두 가지고 있다. 사용하는 방법 역시 동일.
        - 단, 사용자가 입력한 정보의 저장 순서를 유지하는 특징을 추가로 가지고 있다.
    - TreeSet
        - 전체적으로는 HashSet과 동일하다. 단, 인덱스를 자기가 알아서 오름차순으로 자동정렬하는 기능을 가지고 있다.
        
        ```java
        System.out.println(tSet2);
        ```
        
        - 내림차순의 경우,  아래와 같다.
        
        ```java
        System.out.println(((TreeSet<String>)tSet2).descendingSet());
        ```
        
- Map
    
    ![7.png](JAVA_교육/7%204.png)
    
    - .put()을 통해 인덱스를 추가한다.
    단, 기존에 존재하는 키와 일치하면 나중에 put한 키 값이 덮어쓴다.
    - Varname.get(key) 를 통해 Value를 불러올 수 있다.
    - .remove() 를 통해 키와 값 객체를 삭제 처리 할 수 있다.
    - .size() 를 통해 엔트리의 수를 확인할 수 있다.
    - HashMap
        - Map 인터페이스의 기본 구현.
    - HashTable
        - 동기화의 유무만 다를 뿐, HashMap과 동일하다.
    - LinkedHashMap
        - HashTable과 연결 목록의 조합. 인접 참조를 링크해서 체인처럼 관리한다.
    - Properties
        - Key, Value 모두 String 타입이 강제된다.
        - 설정 파일(외부의) 값을 읽어서 어플리케이션에 적용할 때 사용한다.
        외부 파일은 dat, txt, xml이 사용된다.
        - .setProperty() 를 통해 인덱스 값을 추가한다. (Key와 value)
        - .store() 를 통해 엔트리를 외부 파일에 저장한다. (try-catch 필요)
        - .load() 를 통해 인덱스 값을 읽어올 수 있다.
        - dat : 응용프로그램에서 처리 할 정보가 들어있을 시 적용하는 파일의 확장자
        - xml (eXtensible Markup Language) : 데이터를 전달하거나 교환하기 위해 테그(<>)로 이루어진 데이터 교환 포맷.
    - TreeMap
        - TreeSet 과 유사하다. 역시 인덱스를 오름차순으로 자동정렬한다.
    - 출력
        - 출력은 아래의 세 가지 방법으로 가능하다.
            
            ```java
            Set<String> Keys = hMap3.keySet();
            		Iterator<String> keyIter = Keys.iterator();
            		
            		while(keyIter.hasNext()) {
            			String key = keyIter.next();
            			String value = hMap3.get(key);
            			System.out.println(key + " = " + value);
            		}
            ```
            
        - KeySet()을 이용하여 Key값만 따로 Set으로 지정하고, Set게열에서 제공하는 iterator() method로 키에 대한 목록을 만든다. 
        이후, while문을 통해 iterator로 목록을 출력할 수 있다.
            
            ```java
            Collection<String> values = hMap3.values();
            
            	Iterator<String> valueIter = values.iterator();
            		while(valueIter.hasNext()) {
            			System.out.println(valueIter.next());
            		}
            
            	Object[] valueArr = values.toArray();
            		for(int i = 0; i < valueArr.length; i++) {
            			System.out.println(i + " : " + valueArr[i]);
            		}
            ```
            
        - 우선 저장된 value 객체들만 values()로 Collection으로 만든 뒤, Iterator()로 목록을 만들어서 처리하거나 배열로 만들어서 처리할 수 있다.
            
            ```java
            Set<Map.Entry<String, String>> set = hMap3.entrySet();
            		Iterator<Map.Entry<String, String>> entryIter = set.iterator();
            		
            		while(entryIter.hasNext()) {
            			Map.Entry<String, String> entry = entryIter.next();
            			System.out.println(entry.getKey() + " : " + entry.getValue());
            		}
            ```
            
        - Map의 entrySet()을 이용해서 키와 값을 동시에 처리한 뒤, 
        Iterator를 이용, 키만 따로 뽑아내거나, 값만 따로 뽑아낼 수 있다.
- Iterator
    - 컬렉션에서 값을 읽어오는 방식을 통일된 방식으로 제공하기 위해 사용된다.
    - 반복자라고 불리며, 반복문을 이용해서 목록을 하나씩 꺼내는 방식으로 사용한다.
    - 인덱스로 관리되는 컬렉션이 아닌 경우, 반복문을 사용해서 요소에 하나씩 접근할 수 없기에 인덱스를 사용하지 않고 반복문을 사용하기 위한 목록을 만들어주는 역할을 한다.
    - hasNest() : 다음 요소를 가지고 있는 경우 true, 더 이상 요소가 없을 때 false를 반환
    - next() : 다음 요소를 반환하고 다음 요소 전으로 커서를 옮긴다.
    
    <aside>
    💡 Ascending : 오름차순 
    Descending : 내림차순
    
    </aside>
    

# 22. 스레드(Thread)

- 스레드의 정의
    - 스레드는 프로세스 내에서 할당 된 자원을 이용해 실제 작업을 수행하는 작업 단위.
    - 모든 프로세스는 하나 이상의 스레드를 가지며 각각 독립적인 작업단위를 지닌다.
    - 메인 스레드는 Java에서는 main method와 동일하게 생각할 수 있다.
    - 이후 필요에 의해 작업 스레드를 만들어 멀티 스레드를 이용할 수 있다.
    - 멀티 프로세스는 각각의 프로세스를 독립적으로 시행하며, 멀티 스레드는 하나의 프로세스 내에서 여러 스레드가 동시에 작업을 수행한다.
    - 싱글 스레드는 메인 스레드 하나만으로 작업을 처리하며, 멀티 스레드는 추가적인 스레드를 이용, 병렬적으로 작업을 처리한다.
    
    ![8.png](JAVA_교육/8%203.png)
    
- 스레드의 생성
    - 스레드의 생성은 Thread를 상속받은 후 run() 메소드를 오버라이딩 하거나, 
    Runnable 인터페이스를 구현한 후 run() 메소드를 오버라이딩 하는 방법이 있다.
    - 이후 외부 클래스의 인스턴스를 생산한 후, 해당 레퍼런스 변수를 매개변수로 하는 Thread 레퍼런스 변수를 생성. 해당 레퍼런스에 참조연산자 start(); 를 이용하여 thread를 시작할 수 있다.
    
    ```java
    CalssName VarName = new ClassName();
    
    Thread Varname2 = Varname; 
    Thread Varname2 = new Thread(Varname);
    ```
    
    <aside>
    💡 run() method의 경우, 다른 스레드가 아닌 메인 스레드에서 메소드를 호출하는 방식으로 등장한다.
    
    </aside>
    
- 스레드의 제어 (Thread Control)
    - wait() 를 통해 Thread를 일시정지  시킬 수 있다. 
    try-catch 구문으로 감싸줘야 한다.
    - notify() 를 통해 정지 중인 모든 Thread를 다시 구동 시킬 수 있다.
    - .setPriority() 를 통해 1~10 사이의 우선순위를 정할 수 있다. 
    (단, 하드웨어의 발달로 인해 큰 차이를 확인하기는 어렵다.)
    - .sleep() 를 통해 스레드의 수행 간 지연을 밀리세컨드 단위로 부여할 수 있다.
    try-catch 구문으로 감싸줘야 한다.
    - .join() 메소드를 통해 지정한 스레드가 종료될 때까지 나머지 스레드들의 종료를 대기 시킬 수 있다.
    - Daemon Thread를 통해 데몬 스레드 이외의 스레드들이 모두 종료되면 강제로 종료되는 스레드를 작성할 수 있다.
    단, start() 메소드 호출 전에 호출해야 한다. 그렇지 않으면 IllegalMonitorStateException이 발생한다.
        - 스레드 스케쥴링
            - 스레드 개수가 코어의 수보다 많은 경우, 스레드를 어떤 순서로 동시성을 실행할 것인가를 결정하는 것. 스케쥴링에 의해 스레드들은 번갈아 가며 run() 메소드를 조금씩 실행한다.
            - round-robin(순환 할당 방식) : 시간 할당량을 정하여 하나의 스레드를 정해진 시간만큼 실행시키며, JVM에 의해 정해진다. (코드로 제어 불가능하다)
            - 우선 순위 방식(priority) : 우선 순위가 높은 스레드가 작업 시간을 더 많이 가지도록 할 수 있다.
- 동기화(Synchronized)
    - 한 번에 한 개의 스레드만 프로세스의 공유 자원(인스턴스)에 접근할 수 있도록 하는 것. 스레드 컨트롤을 이용(.wait와 .notify 등) 한다.
    
    ![9.png](JAVA_교육/9%203.png)
    
    - 동기화는 접근제어자와 메소드 사이에 synchronized 예약어를 붙여 사용한다.

[단축키](JAVA_교육/단축키.md)

[예제들](JAVA_교육/예제들.md)