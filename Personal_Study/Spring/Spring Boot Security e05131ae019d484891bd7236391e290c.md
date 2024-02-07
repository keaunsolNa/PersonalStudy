# Spring Boot/Security

<aside>
💡 기존 Spring Legacy에서 Configuration의 어려움을 극복한 것이 SpringBoot
Spring Legacy를 기준으로 다른 점을 확인하는 방식으로 이해하자.

</aside>

# 설정

- 설치 및 실행
    - sts 4.15.1을 다운로드 한다.
        
        [Spring Tools 4 is the next generation of Spring tooling](https://spring.io/tools)
        
    - dev 폴더(.jar 파일 위치한 레포지토리)에서 쉬프트 우클릭으로 PowerShell 터미널에 진입한다.
    - java -jar spring 후 탭을 눌러 파일 확인
    PS C:\dev> java -jar .\spring-tool-suite-4-4.15.1.RELEASE-e4.24.0-win32.win32.x86_64.self-extracting.jar
    - 이후 엔터키로 압축 해제 진행
    - Spring initializr 사용
        - Spring initializr에서 Project Metadata와 Dependencies를 포함한 설정을 지정한다.
            
            [Spring Initializr](https://start.spring.io/)
            
        - Project  : Maven과 Gradle 중 선택하여 지정. 프로젝트를 빌드하고 배포하는 툴을 선택한다.
        - Language : 사용할 언어 (Java, Kotlin, Groovy. 현재 추세는 Kotlin으로 추세가 옮겨가는 중) 지정
        - Spring boot : Spring 버전 설정(2.7.1)
        - Project Metadata
        
        Group : artifact를 만든 조직의 id로 패키지명과 같은 포맷으로 "."으로 계층 구조를 표시한다.
        
        Artifact : Group에서 만든 artifact들을 구분하기 위한 id로 버전 정보를 생략한 jar파일의 이름. 
        
        Name : 빌드되어서 나오는 결과물의 이름(ex : testProject)
        
        Description artifact에 대한 설명(ex : test project for Spring Boot)
        
        Pacjage name : 패키지 이름 설정. 3레벨 이상으로 설정하며 group id 하위에 artifact name 구조를 사용한다.
        
        Packaging : artifact가 배포되는 형태로, JAR와 WAR 중 선택 가능 
        
        Java : java JDK 버전 설정(11)
        
        - Dependencies 메뉴의 ADD로 DI할 라이브러리 탐색 및 지정
        - GENERATE로 압축 파일 다운로드
        - 압축 파일 프로젝트에 붙여넣기
        - 압축풀기 후 STS에서 File - Import - Projects from Folder or archive 순서로 생성한 설정 파일 Import 지정
        - Import source로 압출파일 디렉토리 지정(폴더 내부)
    - Eclipse의 Starter 사용
        - Spring Starter Project를 통해 신규 프로젝트를 생성한다.
        - Initializr와 상세 설명은 동일하지만, default 값이 다른 부분이 있음에 유의하자.
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled.png)
            
        - DI는 아래와 같다.
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%201.png)
            
- CMD 명령어
    - 기본 명령어
    cd.. : 상위 디렉토리
    cd dev : 하위 디렉토리
    cls : Clear Screen
    dir : 현재 디렉토리의 파일 확인
    - jsp : 현재 구동 중인 백그라운드 프로그램 목록 과 pid 번호 확인
    - taskkill -f /pid (pid번호값) : 해당 프로그램 종료
- yml(ymal) 파일
    - 기존의 propertis 파일을 대체하여 사용한다.
    - 장점으로는 중복되어 작성할 것들을 줄일 수 있으며, UTF-8이 기본 인코딩 방식으로 지원되며, 가독성이 좋아진다는 장점이 있다.
    - 서버 포트 설정
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%202.png)
        
    - 오라클 드라이버 설정
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%203.png)
        
    - 배너 OFF/변경/설정
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%204.png)
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%205.png)
        
    - mybatis.xml 파일 등록
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%206.png)
        
- Plugin
    - Eclipse Web Developer Tools 3.26 : springBoot에서 지원하지 않는 HTML, CSS, JSON Editor를 지원한다.
    - Eclipse XML Editors and Tools 3.26 : XML, XML Schema, DTD Editor를 지원한다.
    - MyBatipse 1.2.5 : MyBatis XML 파일을 별도의 propertis를 설정하지 않아도 생성할 수 있도록 지원한다.
- Configuration
    - SpringBoot의 @Configuration 설정은 무척 간단하다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%207.png)
        
    - 위 @SpringBootApplication Annotation을 통해 JDBC API, Oracle Driver, Spring Boot DevTools, Spring Web, Thymeleaf, Mybatis Famework 설정이 모두 가능하다.
    - @SpringBootApplication Annotation의 상세 Annotation은 아래와 같다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%208.png)
        

# CRUD

- Controller
    - 기존 Spring 방식과 크게 달라진 건 없다. class에 @Controller Annotation을 통해 설정하고, Request,Get,PostMapping 등으로 요청을 받고, ModelAndView, @ResponseBody 등으로 Response 한다.
- Service
    - 트랜잭션 처리가 무척 간단해졌다. 별도로 전파행위옵션과 격리 레벨을 설정하지 않아도 @Transactional Annotation을 통해 default값인 REQUIRED와 DEFAULT로 설정해준다.
- DAO(Mapper)
    - Mapper Interface 생성
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%209.png)
        
    - Mapper Bean 생성자 방식 생성과 DI. Mapper.xml 에서 실행될 쿼리 return값 지정
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2010.png)
        
    - @Configuration을 통해 MapperScan 설정과 Mapper 하위 구현체 생성
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2011.png)
        
    - xml 파일은 이전 과정과 동일하다.

# 단위 테스트(TDD, 테스트 주도 개발)

- 정의, 활용
    - 동시 개발이 진행되는 프로젝트의 상황 상, 앞선 단위에 구애받지 않고 독립된 단위의 기능 테스트를 할 수 있다는 장점이 있다.
    - @SpringBootTest Annotation을 통해 해당 class가 test 파일임을 명시하며,  하위 구현체 생성을 위해 테스트 하려는 계층 파일과 동일한 패키지 구조의 test 폴더 아래에 만들어야 한다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2012.png)
        
    - @RunWith(SpringRunner.class)를 통해 JUnit이 SpringRunner를 사용해 스프링에서 제공하는 @autowired, @Bean과 같은 Annotation들을 사용할 수 있도록 확장한다.
    - @ContextConfiguration(classes = {}_ Annotation을 통해 테스트할 게층 클래스와 동일한 설정 구조를 가질 수 있도록 확장한다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2013.png)
        
    - @Autowirerd Annotation을 통해 Bean 객체를 사용할 수 있다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2014.png)
        
    - @BeforeAll Annotation을 통해 테스트 시행 전 한 번 시행할 메서드를 활용할 수 있다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2015.png)
        
    - @BeforeEach를 통해 테스트를 시행할 때마다 반복해서 시행할 메서드를 활용할 수 있다.
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2016.png)
    
    <aside>
    💡 Mock 객체는 MVC 구조 테스트를 위해 http 요청을 보낼 수 있도록 허위 요청을 보내는 객체
    필드 방식으로 선언할 수 있으며, 내장객체 MockMvcBuilders.standaloneSetup().build(); 를 통해 Mock 객체를 생성한다.
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2017.png)
    
    </aside>
    
    - @Test Annotation을 통해 시행할 테스트 객체를 지정한다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2018.png)
        
    - @Disabled Annotation을 통해 해당 테스트 객체를 사용하지 않도록 설정할 수 있다.

# ThymeLeaf

- 정의
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2019.png)
    
    - SpringBoot에서 공식적으로 지원하는 View 탬플릿.
    - 웹 및 독립 실행형 환경 모두를 위한 최신 서버 측 Java 템플릿 엔진
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2020.png)
        
    - <html> 태그 안에 <html xmlns:th="http://www.thymeLeaf.org"> 형식으로 네임스키마를 추가, 활용할 수 있다.
    - 모든 ThymeLeaf 문법은 독립적으로 시행되지 않고, 태그 안에서 사용된다.
- 특징
    - JSP와 달리 Thymeleaf 문서는 html확장자를 가지고 있어 jsp처럼 Servlet이 문서를 표현하는 방식이 아니기에 서버 없이도 동작 가능하다.
    - SSR 탬플릿으로 백엔드에서 HTML을 동적으로 생성한다.
    - Natural Templates을 제공한다. 즉, HTML의 기본 구조를 그대로 사용할 수 있으며 HTML 파일을 직접 열어도 동작한다.
    - 개발 시 디자인과 개발이 분리되어 작업 효율이 좋다.
    - WAS를 통하지 않고도 파일을 웹 브라우저를 통해 열 수 있다.
    - 객체(Object, List, Map…)를 다루기 유용한 표현식이 제공한다.
- 표현식
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2021.png)
    
    - [][] 태그 : 
    → el 태그로 꺼낸 값을 문자열로 변경해준다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2022.png)
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2023.png)
        
    - th:replace 
    → jsp:include 와 동일하다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2024.png)
        
    - th:include
    → jsp:include와 동일하다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2025.png)
        
    - th:each
    → foreach 문과 동일하다.
    - th:text
    → el 태그 안의 내용을 text 형태로 출력한다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2026.png)
        
    - th:utext
    → el 태그 안의 내용을 태그형태로 출력한다.
- 제어문
    - 
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2027.png)
    
- SpringEl
    - 변수 표현식(#{…})에서 SpringEl을 사용하여 단순한 변수가 아닌 Object, List, Map같은 객체의 값들을 불러올 수 있다.
    - 객체 그래프 탐색 기법을 사용할 때 유용하다.
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2028.png)
    
- 기타
    - 
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2029.png)
    

# SpringSecurity

- HTTP와 HTTPS
    - HTTPS의 S는 SECURE LAYER가 추가된 것.
    - HTTP로만 요청 시 스니핑으로 PACKET을 빼돌릴 수 있다.
    - 대칭키 사용시 CLIENT의 암호화키로 서버의 암호화까지 해제할 수 있다.
    - HTTPS는 비대칭키(공개키)로 CLIENT와 SERVER의 암호화키를 다르게 하는 방식으로 SECURE LAYER를 추가한다.
- 인증(authentication)과 인가(authorization)
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2030.png)
    
    - 인증은 Login시 이루어지며 인증된 유저에게 JSessionID(Session 방식), accessKeyToken(Token 방식)이라는 권한을 부여하며, 해당 권한을 통해 인가가 이루어진다.
    - 인가(authorization) 절차는 다음과 같다. 
    1. authentication 절차를 통해 AccessToken 생성. 
    2. 유저 request시 accessToken을 첨부하여 요청
    3. 서버는 유저가 보낸 AccessToken 복호화
    4. 복호화된 데이터를 통해 userId 획득
    5. userId를 사용하여 DB에서 유저의 권한(Permission) 확인
    6. 유저가 충분한 권한이 있을 경우 요청 처리
    7. 유저가 권한을 가지고 있지 않으면 Unauthorized Response(401) 혹은 다른 에러 코드 전송
    - Session 방식
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2031.png)
        
        - Session 방식의 가장 큰 장점은 Server 쪽 JSESSIONID를 폐기함으로서 로그인과 권한에 대한 인증 제어를 할 수 있다는 점, 개인정보에 안전하다는 점이 있다.
        - 단, SESSION 방식은 서버의 부담이 크다.
        - 또한, 서버가 늘어날 경우 세션 클러스터링 기술(round-robin, L4스위치, 로드밸런서)이 필요하다.
        - Session 인증 방식은 보안성과 단일 서버에 적합하다.
        - 세션 클러스터링 기술 :
        WAS SERVER가 다수일 경우, 하나의 토큰을 request 요청을 받는 WAS SERVER에 따라 이동하여 관리하는 방식.
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2032.png)
            
    - Token 방식
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2033.png)
        
        - 토큰 방식은 JWT와 OAUTH2.0의 2가지 방식이 있다.
        - 토큰 방식의 가장 큰 장점은 권한을 Server가 아닌 Client에게 Token을 통해 부여함으로 Server의 부담이 적다는 점에 있다.
        - TOKEN은 사설로 직접 만드는 방법, 인증 기관을 통해 발급받는 방법이 있다.
        - JWT 방식
            - 단, Token을 Server가 아닌 Client에서 관리함으로 Server 쪽에서 Token을 관리할 수 없다.
            - Token은 header, payload, verify signature의 3부분으로 이루어져있다.
            - Header에는 암호화 알고리즘이 들어있다.
            - payload에는 유효기간, 권한, 레벨 등의 간단한 개인정보가 들어있다.
            - verify signature는 payload와 server의 정보를 통해 Token의 확인에 필요한 부분이다.
        - OAUTH2.0 방식
            - 토큰을 하나가 아닌 두 개 발급하며, 각각의 토큰을 accessToken, refreshToken이라 부른다.
            - 이 중 accessToken은 Client에게 acc 영역에 10~30 분 간의 짧은 기간 동안 유효한 토큰이며 refreshToken은 DB에 refresh 영역에 2주 ~ 한달간 유효한 토큰이다.
            - accessToken이 만료됐을 경우, refreshToken으로 accessToken을 쉽게 재발급할 수 있다.
            - 또한, refreshToken을 제어하는 방식으로 인증 관리 역시 가능하다.
            - OAUTH2.0(bearer) 방식은 payload에 토큰 정보가 없다.
- 권한 설정
    - Configuration 설정
        - 시큐리티 용 설정 파일은 @Configuration이 아닌 @EnableWebSecurity Annotation을 이용하여 등록한다.
        - 설정 용 폴더에 클래스를 만든 뒤, WebSecurityConfigurerAdapter를 상속 받는다.
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2034.png)
            
        - 이후 WebSecurityConfigurerAdapter 추상 메소드(Adapter)를 @Override 하여 원하는 기능을 구현한다.
    - WebSecurityConfigurerAdapter의 Abstract Method
        - configure
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2035.png)
            
            - 매개변수로 받는 class에 따라 다른 기능을 구현한다.
            - WebSecurity
                
                ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2036.png)
                
                - .ignoring() : 시큐리티 설정을 무시할 정적 리소스 등록(resources 안의 static 폴더 안의 파일들)
                    
                    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2037.png)
                    
                - 
            - HttpSecurity
                
                ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2038.png)
                
                - .csrf() : 기본 설정은 on()으로 가동중. 교차사이트 요청 위조를 막기 위해 Spring Security에서 제공하는 내장 method. 
                프로젝트에서는 기능의 편의성을 위해 disalbe()로 해당 속성 불능화한다.
                    
                    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2039.png)
                    
                - .authorizeRequests()
                    
                    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2040.png)
                    
                    - Request에 대한 권한 설정을 Method Chaining 기술로 설정한다.
                        
                        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2041.png)
                        
                    - .antMatchers().authenticated() 
                    
                    → antMatchers() 안의 페이지는 인증된 접근만 가능하다는 설정
                    - .antMatchers(HttpMethod.GET, "/menu/**").hasRole("MEMBER")
                    
                    → “menu” 페이지에 대한 GET 요청에 대해 ROLE_MEMBER에 해당하는 권한만 허용해주겠다는 설정
                    - .anyRequest().permitAll()
                    
                    → antMatchers를 통해 설정하지 않은 페이지는 어떤 권한이라도 접근 가능하다는 설정
                    
                    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2042.png)
                    
                    - Spring Security에서 제공하는 기본 로그인 form이 아닌 다른 페이지를 사용하기 위한 내장 Method
                    - .formLogin()														
                    .loginPage("/member/login")
                    
                    → .formLogin() 을 통해 Security 기본 로그인 페이지가 아닌 직접 설정한 페이지로 로그인 페이지를 구현하겠다는 설정임을 명시.
                    
                    → .loginPage() 안의 매개변수는 loginPage로서의 의미이자 해당 login Page에서 submit 요청하는 경로의 의미를 가진다.
                    
                    → 이후 .loginPage("/member/login")를 통해 ()안의 페이지를 로그인 기본 페이지로 삼는다.  즉, 권한이 획득되지 않은 상태에서도 이용 가능한 페이지라는 의미)
                    - .successForwardUrl("/")
                    → 로그인 성공시 Forward할 페이지 Url 주소 설정
                        
                        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2043.png)
                        
                    - Logout시 실행할 기능들을 정의하는 설정 내장 Method
                    
                    → .logout()을 통해 해당 설정들이 로그아웃에 대한 설정임을 명시
                    
                    → .logoutRequestMatcher(new AntPathRequestMatcher())을 통해 AntPathRequestMatcher()의 매개변수 값으로  요청시 로그아웃 처리 
                    
                    → .deleteCookies("JESSIONID")을 통해 로그아웃시 쿠기(JSESSIONID)를 삭제한다는 의미
                    
                    → .invalidateHttpSession(true)을 통해 로그아웃시 세션 정보도 삭제
                    
                    → .logoutSuccessUrl("/") 로그아웃시 해당 URL로 페이지 이동
                    
                    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2044.png)
                    
                    - 에러 및 권한 인증 실패에 대한 설정
                    
                    → .exceptionHandling() 을 통해 해당 설정들이 예외 및 권한 인증 실패에 대한 설정임을 명시
                    
                    → .accessDeniedPage("/common/denied") 을 통해 권한 부족시 해당 페이지로 이동한다.
                    
            - AuthenticationManagerBuilder
                
                ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2045.png)
                
                - 해당 클래스를 매개변수로 받은 configure 메서드를 통해 권한 등록시 인증할 비지니스 로직을 등록한다.
                - 내장 메서드 userDetailsService의 매개변수로 Service객체의 생성자 타입 빈을 주입하고, 내장 메서드 .passwordEncoder가 진행되며 Service 게층에서 권한 관련 비지니스 로직 처리가 수행된다.
                - BCryptPasswordEncoder는 Service가 아닌 Configuration에서 진행된다.
    - Service 계층에서 Authority 설정
        - Service Implements에서 Spring Security가 제공하는 UserDetailsService Interface를 상속받는다.
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2046.png)
            
        - Service Implements를 구현한 Service 계층에서 UserDetails 객체 타입의 method Override
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2047.png)
            
        - GrantedAuthority타입의 List 객체 생성
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2048.png)
            
        - for문을 활용하여 로그인한 유저의 DTO 객체에 권한 설정 .add(new SimpleGrantedAuthority()) 내장 메서드를 활용한다.
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2049.png)
            
        - authorities 변수 출력문 :
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2050.png)
        
        - SpringSecurity는 Service에서 전달받은 DTO객체를 UseDetails 타입의 User라는 객체로 반환해야 한다.
        - 전달에 사용할 User객체는 User 객체를 상속받은 DTO객체를 통해 다룬다.
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2051.png)
            
        - 이후 해당 객체를 모든 값을 담은 생성자 메서드에 기존 DTO객체를 매개변수로 추가하는 방식을 통해 Principal 객체에 더 많은 정보를 제공할 수 있다.
            
            ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2052.png)
            
- SOP(Same Origin Policy)
    
    ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2053.png)
    
    - cors eroor : 외부 출처 리소스를 신용하지 않을 때(주로 크롬에서), 발생하는 에러. 위 그림을 볼 때, chrome에서 출처 B의 fetch/xhr 요청을 거절하는 것.
    - 현대 API는 대체로 REST API 기술을 사용함으로 CORS Error가 발생할 일은 적지만, 발생할 경우 별도 Spring 설정에서 CORS를 허용해주는 과정이 필요하다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2054.png)
        
    - .allowedOrigins()를 통해 요청 주소를 선별하거나, 모든 요청에 허용할 수 있다.

# 배포

- JAR / War 파일로 압축
    - 배포를 원하는 프로젝트를 우클릭 하고 Run As → ㅡMaven Build…. 를 통해 Edit Configuration으로 진입한다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2055.png)
        
    - Goals를 원하는 패키지 이름으로 설정하고 Run 버튼을 통해 배포 가능한 형태의 Jar / War 형태의 파일로 압축 가능하다.
        
        ![Untitled](Spring%20Boot%20Security%20e05131ae019d484891bd7236391e290c/Untitled%2056.png)