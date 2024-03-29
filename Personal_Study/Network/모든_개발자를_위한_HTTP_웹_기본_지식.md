# 모든 개발자를 위한 HTTP 웹 기본 지식

# Section 1. Internet Network

- Internet Communication
    - Internet을 이용하여 통신이 이루어지는 과정을 이해하기 위해서는 IP(Internet Protocol)에 대해 먼저 이해해야 한다.
- IP(Intenet Protocol)
    - IP의 역할
        - 지정한 IP 주소(IP Address)에 데이터 전달
        - Packet이라는 통신 단위로 데이터 전달
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled.png)
            
    - 클라이언트가 Packet을 인터넷의 노드에 보내면 노드 간의 통신을 통해 목적지 Server로 전달된다.
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%201.png)
        
    - 단, IP Protocol은 다음의 한계가 있다
        - 비연결성
            - 패킷을 받을 대상이 없거나 서비스 불능 상태여도 패킷을 전송
        - 비신뢰성
            - 중간에 패킷이 사라질 수 있음
            - 패킷이 순서대로 오지 않을 수 있음
                
                ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%202.png)
                
        - 프로그램 구분
            - 같은 IP를 사용하는 서버에서 통신하는 애플리케이션이 둘 이상인 경우
- TCP, UDP
    - 인터넷 프로토콜 스택의 4계층
        - 애플리케이션 계층 - HTTP, FTP
        - 전송 계층 - TCP, UDP
        - 인터넷 계층 - IP
        - 네트워크 인터페이스 계층
    - 프로토콜 계층
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%203.png)
        
    - TCP/IP 패킷 정보
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%204.png)
        
    - TCP 특징
        - 전송 제어 프로토콜(Transmission Control Protocol)
        - 연결 지향 - TCP 3 way handshake (가상 연결, 물리적 연결은 아니다)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%205.png)
            
        - 데이터 전달 보증
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%206.png)
            
        - 순서 보장
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%207.png)
            
        - 신뢰할 수 있는 프로토콜
        - 현재는 대부분  TCP 사용
    - UDP 특징
        - 사용자 데이터그램 프로토콜(User Datagram Protocol)
        - 하얀 도야지에 비유(기능이 거의 없음)
        - 연결 지향 X - TCP 3 way handshake X
        - 순서 보장 X
        - 데이터 전달 및 순서가 보장되지 않지만, 단순하고 빠름
        - IP와 거의 같다 (PORT, Checksum 정도만 추가)
        - 애플리케이션에서 추가 작업 필요
- PORT
    - PORT : 같은 IP 내에서 프로세스를 구분한다.
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%208.png)
        
    - 0 ~ 65535 : 할당 가능
    - 0 ~ 1023 : Well-Known Port, 사용하지 않는 편이 유리
        - FTP - 20, 21
        - TELNET - 23
        - HTTP - 80
        - HTTPS - 443
- DNS
    - IP는 기억하기 어려우며 변경될 수 있다.
    - DNS : 도메인 네임 시스템, Domain Name System
        - 도메인 명을 IP 주소로 변환한다.
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%209.png)
            

# Section 2. **URI and web browser request flow**

- URI
    - Uniform Resource Identifier
    - URI는 Locator, Name 또는 둘 다 추가로 분류될 수 있다.
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2010.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2011.png)
        
    - URI
        - Uniform : 리소스를 식별하는 통일된 방식
        - Resource : 자원, URI로 식별할 수 있는 모든 것
        - Identifier : 다른 항목과 구분하는 데 필요한 정보
    - URL
        - Uniform
        - Resource
        - Locator : 리소스가 있는 위치를 지정
    - URN
        - Uniform
        - Resource
        - Name : 리소스에 이름을 부여
    - 위치는 변할 수 있지만, 이름은 변하지 않는다.
    - URN 이름만으로 실제 리소스를 찾을 수 있는 방법은 보편화 되어 있지 않다.
    - URL 전체 문법
        
        <aside>
        💡 scheme://[userinfo@]host[:port][/path][?query][#fragment]
        
        </aside>
        
        - [https://www.google.com:443/search?q=hello&hl=ko](https://www.google.com/search?q=hello&hl=ko)
            - 프토토콜(https)
            - 호스트명(www.google.com)
            - 포트 번호(443)
            - 패스(/search)
            - 쿼리 파라미터(q=hello&hl=ko)
        - scheme
            - 주로 프로토콜 사용
            - 프로토콜 : 어떤 방식으로 자원에 접근할 것인가 하는 약속 규칙
            - http는 80포트, https는 443 포트를 주로 사용. 포트는 생략 가능
            - https는 http에 보안 추가(HTTP Secure)
        - userinfo
            - URL에 사용자정보를 포함해서 인증
            - 거의 사용하지 않음
        - host
            - 호스트명
            - 도메인명 또는 IP 주소를 직접 사용가능
        - PORT
            - 포트(PORT)
            - 접속 포트
            - 일반적으로 생략, 생략시 http는 80, https는 443
        - path
            - 리소스 경로(path), 계층적 구조
        - query
            - key=value 형태
            - ?로 시작, &로 추가 가능
            - query parameter, query string 등으로 불림.
            - 웹 서버에 제공하는 파라미터, 문자 형태
        - fragment
            - html 내부 북마크 등에 사용
            - 서버에 전송하는 정보는 아님
    - URL schema
- Web browser request flow
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2012.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2013.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2014.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2015.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2016.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2017.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2018.png)
    

# Section 3. Basic of HTTP

- Everything is HTTP
    - HTTP : HyperText Transfer Protocol
    - HTTP 메시지에 모든 것을 담아 전송한다.
        - HTML, TEXT
        - IMAGE, 음성, 영상, 파일
        - JSON, XML(API)
        - 거의 모든 형태의 데이터 전송 가능
        - 서버 간 데이터 전송에도 대부분 HTTP 사용
    - HTTP 역사
        - HTTP/0.9 : 1991년 : GET Method만 지원, HTTP Header 미 포함
        - HTTP/1.0 : 1996년 : Method, Header 추가
        - HTTP/1.1 : 1997년 : 가장 많이 사용되는 버전
            - RFC2068(1997) → RFC2616(1999) → RFC7230 ~ 7235(2014)
        - HTTP/2 : 2015년 : 성능 개선
        - HTTP/3 : 진행 중 : TCP 대신 UDP 사용, 성능 개선
    - 기반 프로토콜
        - TCP : HTTP/1.1, HTTP2
        - UDP : HTTP/3
        - 현재 HTTP/1.1를 주로 사용, HTTP/2,3 도 점점 증가 추세
    - HTTP 특징
        - 클라이언트 서버 구조
        - Stateless, Connectionless
        - HTTP 메시지
        - 단순함, 확장 가능
- Structure of Client Server
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2019.png)
    
    - Client와 Server를 개념적으로 분배한 후 Cliecnt와 Server가 각각 독립적으로 발전이 가능해졌다.
- Stateful, Stateless
    - Stateless
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2020.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2021.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2022.png)
        
        - Server가 Client의 상태를 보존하지 않는다.
            - 장점 : 서버 확장성 높음(Scale out)
            - 단점 : Client가 추가 데이터 전송
    - Stateful
        - Server가 Cliecnt의 상태를 유지한다.
        - 항상 같은 서버가 유지되어야 한다.
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2023.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2024.png)
            
    - Stateless의 한계
        - 모든 것을 무상태로 설계 할 수 있는 경우도, 없는 경우도 있다
            - Stateless : 로그인이 필요 없는 단순 서비스 소개 화면
            - Stateful : 로그인
        - 로그인한 사용자의 경우 로그인 했다는 상태를 서버에 유지
        - 일반적으로 브라우저 쿠키와 서버 session등을 사용해서 상태 유지
        - 상태 유지는 최소한만 사용
- Connection-less
    - Connection-less 방식의 장점
        - HTTP는 기본이 연결을 유지하지 않는 모델이다
        - 일반적으로 초 단위 이하의 빠른 속도로 응답한다
        - 1시간 동안 수천명이 서비스를 사용해도 실제 서버에서 동시에 처리하는 요청은 수십개 이하로 매우 적다
        - 서버 자원을 매우 효율적으로 사용할 수 있다
    - Connection-less 방식의 한계와 극복
        - TCP/IP 연결을 새로 맺어야 한다.  (3 way handshake 시간 추가)
        - 웹 브라우저로 사이트를 요청하면 HTML뿐만 아니라 자바스크립트, css, 추가 이미지 등 수 많은 자원이 함께 다운로드된다.
        - 지금은 HTTP 지속 연결(Persistent Connections(Keep-Alive))로 문제를 해결한다
        - HTTP/2, HTTP/3에서 더 많은 최적화가 이루어진다.
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2025.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2026.png)
            
- HTTP Message
    - HTTP 요청 메시지와 응답 메시지는 구조가 다르다.
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2027.png)
        
    - HTTP Message의 공식 스펙
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2028.png)
        
        - Start-Line, 시작 라인 구조 (요청 메시지)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2029.png)
            
            - start-line = **request-line** / status-line
            - request-line = method SP(공백) request-target SP HTTP-version CRLF(엔터)
            - HTTP 메서드(GET)
                - 종류 : GET, POST, PUT, DELETE…
                - 서버가 수행해야 할 동작을 지정
                    - GET : 리소스 조회
                    - POST : 요청 내역 처리
            - 요청 대상(/search?q=hello&hl=ko)
                - absolute-path[?query](절대경로[?쿼리])
                - 절대경로 = “/” 로 시작하는 경로
            - HTTP version (HTTP/1.1)
                - HTTP Version (1.1, 1.2, 3.0 등)
            - 단, HTTP 요청 메시지도 Body를 가질 수 있다.
        - Start-Line, 시작 라인 구조 (응답 메시지)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2030.png)
            
            - start-line = request-line / **status-line**
            - status-line = HTTP-version SP status-code SP reason-phrase CRLF
            - HTTP 버전(HTTP-version)
            - HTTP 상태 코드(status-code) : 요청의 성공, 실패
                - 200 : 성공
                - 400 : 클라이언트 요청 오류
                - 500 : 서버 내부 오류
            - 이유 문구(reason-phrase)
                - 사람이 이해할 수 있는 짧은 상태 코드 설명 글
    - HTTP Header
        - header-field = field-name “:” OWS field-value OWS (OWS: 띄어쓰기 허용)
        - field-name은 대소문자를 구분하지 않는다.
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2031.png)
            
        - 용도
            - HTTP 전송에 필요한 모든 부가정보
                - ⇒ 메시지 바디의 내용, 메시지 바디의 크기, 압축, 인증, 요청 클라이언트 정보, 서버 애플리케이션 정보, 캐시 관리 정보 등
            - 표준 헤더가 너무 많다
            - 필요시 임의의 헤더 추가도 가능
                - [https://en.wikipedia.org/wiki/List_of_HTTP_header_fields](https://en.wikipedia.org/wiki/List_of_HTTP_header_fields)
    - HTTP Message Body
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2032.png)
        
        - 실제 전송할 데이터
        - HTML 문서, 이미지, 영상, JSON 등등 byte로 표현할 수 있는 모든 데이터 전송 가능

# Section 4. HTTP Method

- Make a HTTP API
    - URI 설계의 가장 핵심은 Resource를 식별하는 것에 있다.
        - Resource란 회원 정보 관리 API의 회원 그 자체다
        - Resource는 회원이라는 Resource만 식별하면 되므로, 회원 Resource를 URI에 mapping하는 것이 좋다.
    - API URI 설계
        - 회원 목록 조회 / members
        - 회원 조회 /members/{id}
        - 회원 등록 /members/{id}
        - 회원 수정 /members/{id}
        - 회원 삭제 /members/{id}
            - 계층 구조상 상위를 Collection으로 보고 복수 단어 사용을 권장한다.(member → members)
            - 행위(method)에 대한 구분은 HTTP method를 통해 수행한다.
    - 위 방식대로 URI를 설계했을 때 중요한 것은 Resource와 행위를 분리하는 것
        - URI는 Resource만 식별
        - Resource와 해당 Resource를 대상으로 하는 행위를 분리
            - Resource : 회원
            - 행위 : 조회, 등록, 삭제, 변경
        - Resource는 명사, 행위는 동사
- HTTP Method - GET, POST
    - HTTP Method 종류
        - 주요 method
            - GET : Resource 조회
            - POST : 요청 데이터 처리, 주로 등록에 사용
            - PUT : Resource를 대체, 해당 Resource가 없으면 생성
            - PATCH : Resource 부분 변경
            - DELETE : Resource 삭제
        - 기타 method
            - HEAD : GET과 동일하지만 메시지 부분을 제외하고, 상태 줄과 헤더만 반환
            - OPTIONS : 대상 Resource에 대한 통신 기능 옵션(method)을 설명(주로 CORS에서 사용)
            - CONNECT : 대상 Resource로 식별 되는 서버에 대한 터널을 설정
            - TRACE : 대상 Resource에 대한 경로를 따라 메시지 루프백 테스트를 수행
    - GET
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2033.png)
        
        - Resource 조회
        - 서버에 전달하고 싶은 데이터는 query를 통해 전달
        - 메시지 바디를 사용해서 데이터를 전달할 수 있지만, 지원하지 않는 곳이 많아 권장되지 않음
    - POST
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2034.png)
        
        - 요청 데이터 처리
        - 메시지 body를 통해 서버로 요청 데이터 전달
        - 서버는 요청 데이터를 처리
            - 메시지 body를 통해 들어온 데이터를 처리하는 모든 기능 수행
        - 주로 전달된 데이터로 신규 Resource 등록, 프로세스 처리에 사용
    - POST가 요청 데이터를 처리하는 방식
        - SPEC : POST method는 대상 Resource가 resource의 고유 한 의미 체계에 따라 요청에 포함 된 표현을 처리하도록 요청한다
        - POST가 사용되는 기능의 예시는 다음과 같다
            - HTML 양식에 입력 된 필드와 같은 데이터 블록을 데이터 처리 프로세스에 제공
            - 게시판, 뉴스 그룹, 메일링 리스트, 블로그 또는 유사한 기사 그룹에 메시지 게시
            - 서버가 아직 식별하지 않은 새 resource 생성
            - 기존 자원에 데이터 추가
                - 단, Resource URI에 POST 요청이 오면 요청 데이터를 어떻게 처리할지 Resource마다 따로 정해야 한다.
    - POST 정리
        1. 새 Resource 생성(등록)
        2. 요청 데이터 처리
        3. 다른 method로 처리하기 애매한 경우
- HTTP Method - PUT, PATCH, DELETE
    - PUT
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2035.png)
        
        - Resource를 대체
            - Resource가 있으면 **완전히 대체**
            - Resource가 없으면 생성
        - Client가 Resource를 식별한다
            - Client가 Resource 위치를 알고 URI를 지정한다
                - ⇒ POST와의 차이점
                
                ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2036.png)
                
    - PATCH
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2037.png)
        
        - Resource 부분 변경
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2038.png)
            
    - DELETE
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2039.png)
        
        - 리소스를 제거한다.
- Property of HTTP Method
    - HTTP Method의 속성
        - 안전(Safe Methods)
        - 멱등(Idempotent Methods)
        - 캐시가능(Cacheable Methods)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2040.png)
    
    - 안전(Safe)
        - 호출해도 Resource를 변경하지 않는다.
        - 안전은 해당 Resource만 고려한다.
    - 멱등(Idempotent)
        - f(f(x)) = f(x)
        - 호출 횟수에 상관 없이 결과가 동일하다
        - 멱등 메서드
            - GET : 몇 번을 조회해도 조회 결과가 같다.
            - PUT : 결과를 대체한다. 같은 요청을 여러 번 해도 최종 결과는 같다
            - DELETE : 결과를 삭제한다. 같은 요청을 여러 번 해도 삭제된 결과는 동일
            - POST : 멱등이 아니다. 두 번 호출하면 같은 결제가 중복 해서 발생 가능
        - 활용
            - 자동 복구 매커니즘
            - 서버가 TIMEOUT 등으로 정상 응답을 못 주었을 때, 클라이언트가 같은 요청을 다시 해도 되는지의 판단 근거
        - 재요청 중간에 다른 곳에서 리소스 변경 시
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2041.png)
        
    - 캐시가능(Cacheable)
        - 응답 결과 Resource를 캐시해서 사용해도 되는가
        - GET, HEAD, POST, PATCH cache 가능
        - 실제로는 GET, HEAD 정도만 캐시로 사용
            - POST, PATCH는 본문 내용까지 캐시 키로 고려해야 하지만, 구현이 어렵다.

# Section 5. Using HTTP Method

- Send data from client to server
    - 데이터 전송 방식
        - Query parameter를 통한 데이터 전송 방식
            - GET
            - 주로 정렬 필터(검색어)
        - 메시지 바디를 통한 데이터 전송
            - POST, PUT, PATCH
            - 회원 가입, 상품 주문, 리소스 등록, 리소스 변경
    - 서버로 데이터 전송하는 4가지 상황
        - **정적 데이터 조회**
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2042.png)
            
            - 이미지, 정적 텍스트 문서
            - 조회는 GET 사용
            - 정적 데이터는 일반적으로 query parameter 없이 resource 경로로 단순하게 조회 가능
        - **동적 데이터 조회**
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2043.png)
            
            - 주로 검색, 게시판 목록에서 정렬 필터(검색어)
            - 조회 조건을 줄여주는 필터, 조회 결과를 정렬하는 정렬 조건에 주로 사용
            - 조회는 GET 사용
            - GET은 query parameter 사용해서 데이터를 전달
        - **HTML Form을 통한 데이터 전송**
            
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2044.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2045.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2046.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2047.png)
            
            - HTML Form submit 시 POST 전송
                - 회원 가입, 상품 주문, 데이터 변경
            - Content-Type: application/x-www-form-urlencoded 사용
                - form의 내용을 메시지 바디를 통해 전송(key=value, query parameter 형식)
                - 전송 데이터를 url encoding 처리
                    - ex) abc김 → abc%EA%B9%80
                - HTML Form은 Get 전송도 가능
                - Content-Type: multipart/form-data
                    - 파일 업로드 같은 바이너리 데이터 전송시 사용
                    - 다른 종류의 여러 파일과 폼의 내용 함께 전송 가능
                - HTML Form 전송은 GET, POST만 지원
        - **HTTP API를 통한 데이터 전송**
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2048.png)
            
            - 회원 가입, 상품 주문, 데이터 변경
            - 서버 to 서버
                - 백엔드 시스템 통신
            - 앱 클라이언트
                - 아이폰, 안드로이드
            - 웹 클라이언트
                - HTML에서 Form 전송 대신 자바 스크립트를 통한 통신에 사용(AJAX)
                - React, Vue.Js 같은 웹 클라이언트와 API 통신
            - POST, PUT, PATCH : 메시지 바디를 통해 데이터 전송
            - GET: 조회, 쿼리 파라미터로 데이터 전달
            - Content-Type: application/json 주로 사용(사실상 표준)
                - TEXT, XML, JSON 등등
- HTTP API Design Example
    - HTTP API - Collection
        - POST 기반 등록
        - ex) 회원 관리 API 제공
        - API 설계 - POST 기반 등록
            - 회원 목록 /members → GET
            - 회원 등록 /members → POST
            - 회원 조회 /members/{id} → GET
            - 회원 수정 /members/{id} → PATCH, PUT, POST
            - 회원 삭제 /members/{id} → DELETE
        - POST - 신규 자원 등록 특징
            - 클라이언트는 등록될 resource의 URI를 모른다.
                - 회원 등록 /members → POST
                - POST /members
            - 서버가 새로 등록된 resource URI를 생성해준다
                - HTTP/1.1 201 Created
                Location: /members/100
            - 컬렉션(Collection)
                - 서버가 관리하는 resource 디렉토리
                - 서버가 resource의 URI를 생성하고 관리
                - 여기서 컬렉션은 /members
    - HTTP API - Store
        - PUT 기반 등록
        - ex) 정적 컨텐츠 관리, 원격 파일 관리
        - API 설계 - PUT 기반 등록
            - 파일 목록 /files → GET
            - 파일 조회 /files/{filename} → GET
            - 파일 등록 /files/{filename} → PUT
            - 파일 삭제 /files/{filename} → DELETE
            - 파일 대량 등록 /files → POST
        - PUT - 신규 자원 등록 특징
            - 클라이언트가 resource URI를 알고 있어야 한다
                - 파일 등록 /files/{filename} → PUT
                - PUT /files/star.jpg
            - 클라이언트가 직접 resource의 URI를 지정한다
            - 스토어(Store)
                - 클라이언트가 관리하는 resource 저장소
                - 클라이언트가 resource의 URI를 알고 관리
                - 여기서 스토어는 /files
    - HTML FORM 사용
        - 웹 페이지 회원 관리
        - HTML FORM은 GET, POST만 지원
            - 순수 HTML, HTML FORM의 Case
            - AJAX 같은 기술을 사용해서 해결 가능
            - GET, POST만 지원하므로 제약이 있음
        - HTML FORM 사용
            - 회원 목록 /members → GET
            - 회원 등록 폼 /members/new → GET
            - 회원 등록 /members/new, /members → POST
            - 회원 조회 /members/{id} → GET
            - 회원 수정 폼 /members/{id}/edit → GET
            - 회원 수정 /members/{id}/edit, /members/{id} → POST
            - 회원 삭제 /members/{id}/delete → POST
        - 컨트롤 URI
            - GET, POST만 지원하므로 제약 존재
            - 이런 제약을 해결하기 위해 동사로 된 resource 경로 사용
            - POST의 /new, /edit/ delete가 컨트롤 URI
            - HTTP 메서드로 해결하기 애매한 경우 사용(HTTP API 포함)
    - URI 설계 개념
        - 문서(document)
            - 단일 개념(파일 하나, 객체 인스턴스, 데이터베이스 row)
            - ex) members/100, /files/star.jpg
        - 컬렉션(collection)
            - 서버가 관리하는 resource 디렉터리
            - 서버가 resource의 URI를 생성하기 관리
            - ex) /members
        - 스토어(store)
            - 클라이언트가 관리하는 자원 저장소
            - 클라이언트가 resource의 URI를 알고 관리
            - ex) /files
        - 컨트롤러(controller), 컨트롤 URI
            - 문서, 컬렉션, 스토어로 해결하기 어려운 추가 프로세스 실행
            - 동사를 직접 사용
            - ex) /members/{id}/delete

# Section 6. HTTP State Code

- State Code Introduction
    - State code
        - 클라이언트가 보낸 요청의 처리 상태를 응답에서 알려주는 기능
        - 1xx (Informational) : 요청이 수신되어 처리중
        - 2xx (Successful) : 요청 정상 처리
        - 3xx (Redirection) : 요청을 완료하려면 추가 행동이 필요
        - 4xx (Client Error) : 클라이언트 오류, 잘못된 문법등으로 서버가 요청을 수행할 수 없음
        - 5xx (Server Error) : 서버 오류, 서버가 정상 요청을 처리하지 못함
    - 클라이언트가 인식할 수 없는 상태코드를 서버가 반환할 경우
        - 클라이언트는 상위 상태코드로 해석해서 처리
        - 미래에 새로운 상태 코드가 추가되어도 클라이언트를 변경하지 않아도 된다.
        - ex)
            - 299 ??? → 2xx (Successful)
            - 451 ??? → 4xx (Client Error)
            - 599 ??? → 5xx (Server Error)
    - 1xx (Informational)
        - 요청이 수신되어 처리 중
        - 거의 사용하지 않으므로 생략
- 2xx - Success
    - 클라이언트의 요청을 성공적으로 처리
        - 200 : OK
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2049.png)
            
        - 201 : Created
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2050.png)
            
        - 202 : Accepted
            - 요청이 접수되었으나 처리가 완료되지 않았음
            - 배치 처리 같은 곳에서 사용
            - ex) 요청 접수 후 1시간 뒤에 배치 프로세스가 요청을 처리
        - 204 : No Content
            - 서버가 요청을 성공적으로 수행했지만, 응답 페이로드 본문에 보낼 데이터가 없음
            - ex) 웹 문서 편집기에서 save 버튼
            - save 버튼의 결과로 아무 내용이 없어도 된다.
            - save 버튼을 눌러도 같은 화면을 유지해야 한다
            - 결과 내용이 없어도 204 메시지(2xx) 만으로 성공을 인식할 수 있다.
- 3xx - Redirection 1
    - 요청을 완료하기 위해 유저 에이전트의 추가 조치 필요
        - 300 Multiple Choices
        - 301 Moved Permanently
        - 302 Found
        - 303 See Other
        - 304 Not Modified
        - 307 Temporary Redirect
        - 308 Permanent Redirect
    - 웹 브라우저는 3xx 응답의 결과에 Location 헤더가 있으면, Location 위치로 자동 이동(Redirect)한다.
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2051.png)
        
    - 영구 Redirection : 특정 리소스의 URI가 영구적으로 이동 (301, 308)
        - /members → /users
        - /event → /new-event
        - 원래의 URI를 사용하지 않으며, 검색 엔진 등에서도 변경을 인지한다.
        - 301 Moved Permanently
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2052.png)
            
            - Redirect시 요청 method가 GET으로 변하고, 본문이 제거될 수 있음(MAY)
        - 308 Permanent Redirect
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2053.png)
            
            - 301과 기능은 같음
            - Redirect시 요청 method와 본문 유지(처음 POST를 보내면 redirect로 POST)
- 3xx - Redirection 2
    - 일시 Redirection : 일시적인 변경 (302, 307, 303)
        - 리소스의 URI가 일시적으로 변경
        - 따라서 검색 엔진 등에서 URL을 변경하면 안 된다.
        - 주문 완료 후 주문 내역 화면으로 이동
        - 302 Found
            - Redirect시 요청 method가 GET으로 변하고, 본문이 제거될 수 있음(MAY)
        - 307 Temporary Redirect
            - 302와 기능은 동일
            - Redirect시 요청 method와 본문 유지(요청 method를 변경하면 안된다. MUST NOT)
        - 303 See Other
            - 302와 기능은 동일
            - Redirect시 요청 method가 GET으로 변경
    - PRG : Post/Redirect/Get
        - POST로 주문 후에 웹 브라우저를 새로고침한 경우
            - 새로고침은 다시 요청
            - 중복 주문이 될 수 있음
                
                ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2054.png)
                
        - POST로 주문후 새로 고침으로 인한 중복 주문 방지
            - POST로 주문후에 주문 결과 화면을 GET method로 Redirect
            - 새로고침해도 결과 화면을 GET으로 조회
            - 중복 주문 대신 결과 화면만 GET으로 다시 요청
                
                ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2055.png)
                
        - PRG 이후 Redirect
            - URL이 이미 POST → GET으로 Redirect됨
            - 새로 고침 해도 GET으로 결과 화면만 조회
    - 정리
        - 302 Found → Get으로 변할 수 있음
        - 307 Temporary Redirect → method가 변하면 안 됨
        - 303 See Other → method가 GET으로 변경
    - 특수 Redirection (300, 304)
        - 300 Multiple Choices : 사용하지 않음
        - 304 Not Modified
            - 캐시를 목적으로 사용
            - 클라이언트에게 resource가 수정되지 않았음을 알려준다.
                - client는 로컬 PC에 저장된 cache를 재사용한다. (cache로 redirect)
            - 304 응답은 응답에 메시지 바디를 포함하면 안된다. (로컬 cache를 사용해야 하므로)
            - 조건부 GET, HEAD 요청시 사용
- 4xx - Client error, 5xx - Server error
    - 4xx(Client Error)
        - 클라이언트의 요청에 잘못된 문법등으로 서버가 요청을 수행할 수 없는 경우
        - 오류의 원인이 클라이언트에 있음
        - 클라이언트가 이미 잘못된 요청, 데이터를 보내고 있기 때문에, 똑같은 재시도가 실패함
        - 400 Bad Request
            - 클라이언트가 잘못된 요청을 해서 서버가 요청을 처리할 수 없음
            - 요청 구문, 메시지 등등 오류
            - 클라이언트는 요청 내용을 다시 검토하고 보내야 함
            - EX) 요청 파라미터가 잘못되거나, API 스펙이 맞지 않을 때
        - 401 Unauthorized
            - 클라이언트가 해당 리소스에 대한 인증이 필요함
            - 인증(Authentication) 되지 않음
            - 401 오류 발생시 응답에 WWW-Authenticate 헤더와 함께 인증 방법을 설명
        - 403 Forbidden
            - 서버가 요청을 이해했지만 승인을 거부함
            - 주로 인증 자격 증명은 있지만, 접근 권한이 불충분한 경우
        - 404 Not Found
            - 요청 resource를 찾을 수 없음
            - 요청 resource가 서버에 없음
            - 또는 클라이언트가 권한이 부족한 resource에 접근할 때 해당 resource를 숨기고 싶을 때
    - 5xx (Server Error)
        - 서버 오류
        - 서버 문제로 오류 발생
        - 서버에 문제가 있기 때문에 재시도 하면 성공할 수도 있음(복구가 되거나 등등)
        - 500 Internal Server Error
            - 서버 내부 문제로 오류 발생
            - 애매하면 500 오류
        - 503 Service Unavailable
            - 서비스 이용 불가
            - 서버가 일시적인 과부하 또는 예정된 작업으로 잠시 요청을 처리할 수 없음
            - Retry-After 헤더 필드로 얼마뒤에 복구되는지 보낼 수 있음

# Section 7. HTTP Header 1 - Normal Header

- HTTP header overview
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2056.png)
    
    - header-field = field-name ":" OWS field-value OWS (OWS:띄어쓰기 허용)
        - field-name은 대소문자를 구분하지 않는다.
    - 용도
        - HTTP 전송에 필요한 모든 부가정보
            - Ex) 메시지 바디의 내용, 메시지 바디의 크기, 압축, 인증, 요청 클라이언트, 서버 정보, 캐시 관리 정보…
        - 표준 헤더가 너무 많음
            - [https://en.wikipedia.org/wiki/List_of_HTTP_header_fields](https://en.wikipedia.org/wiki/List_of_HTTP_header_fields)
        - 필요시 임의의 헤더 추가 가능
    - RFC2616(과거)
        - 헤더
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2057.png)
            
            - General 헤더 : 메시지 전체에 적용되는 정보
                - Ex) Connection: close
            - Request 헤더 : 요청 정보
                - Ex) User-Agent: Mozilla/5.0 (Macintosh; …)
            - Response 헤더 : 응답 정보
                - Ex) Server: Apache
            - Entity 헤더 : 엔티티 바디 정보
                - Ex) Content-Type : text/html, Content-Length: 3423
        - 바디
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2058.png)
            
            - 메시지 본문(message body)은 엔티티 본문(entity body)을 전달하는데 사용
            - 엔티티 본문은 요청이나 응답에서 전달할 실제 데이터
            - 엔티티 헤더는 엔티티 본문의 데이터를 해석할 수 있는 정보 제공
                - 데이터 유형(html, json), 데이터 길이, 압축 정보 등등
    
    <aside>
    💡 RFC2616 → 폐기됨
    2014년 RFC7230 ~ 7235 등장
    
    </aside>
    
    - RFC723x 변화
        - 엔티티(Entity) → 표현(Representation)
        - Representation = representation Metadata + Representation Data
        - 표현 = 표현 메타데이터 + 표현 데이터
    - RFC7230 - message body
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2059.png)
        
        - 메시지 본문(message body)을 통해 표현 데이터 전달
        - 메시지 본문 = 페이로드(payload)
        - 표현은 요청이나 응답에서 전달할 실제 데이터
        - 표현 헤더는 표현 데이터를 해석할 수 있는 정보 제공
            - 데이터 유형(html, json), 데이터 길이, 압축 정보 등등
- Expression
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2060.png)
    
    - **Content-Type : 표현 데이터의 형식**
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2061.png)
        
        - 미디어 타입, 문자 인코딩
            - text/html; charset=utf-8
            - application/json
            - image/png
    - **Content-Encoding : 표현 데이터의 압축 방식**
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2062.png)
        
        - 표현 데이터를 압축하기 위해 사용
        - 데이터를 전달하는 곳에서 압축 후 인코딩 헤더 추가
        - 데이터를 읽는 쪽에서 인코딩 헤더의 정보로 압출 해제
            - gzip
            - deflate
            - identity(압축 하지 않음)
    - **Content-Language : 표현 데이터의 자연 언어**
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2063.png)
        
        - 표현 데이터의 자연 언어를 표현
            - ko
            - en
            - en-US
    - **Content-Length : 표현 데이터의 길이**
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2064.png)
        
        - 바이트 단위
        - Transfer-Encoding(전송 코딩)을 사용할 경우에는 Content-Length를 사용하면 안 된다.
    - 표현 헤더는 전송, 응답 둘 다 사용한다.
- Content Negotiation
    - 클라이언트가 선호하는 표현 요청
        - Accept : 클라이언트가 선호하는 미디어 타입 전달
        - Accept-Charset : 클라이언트가 선호하는 문자 인코딩
        - Accept-Encoding : 클라이언트가 선호하는 압축 인코딩
        - Accept-Language : 클라이언트가 선호하는 자연 언어
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2065.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2066.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2067.png)
            
    - 협상 언어는 요청시에만 사용한다.
    - 협상과 우선순위
        - Quality Values(q)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2068.png)
            
            - Quality Values(q) 값 사용
            - 0 ~ 1, 클수록 높은 우선순위
            - 생략하면 1
            - Accept-Language: ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7
                1. ko-KR;q=1 (q생략)
                2. ko;q=0.9
                3. en-US;q=0.8
                4. en;q=0.7
                    
                    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2069.png)
                    
        - Quality Values(q)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2070.png)
            
            - 구체적인 것이 우선한다.
                - Accept: text/*, text/plain, text/plain;format=flowed, **/**
                    1. text/plain;format=flowed
                    2. text/plain
                    3. text/*
                    4. */*
        - Quality Values(q)
            - 구체적인 것을 기준으로 미디어 타입을 맞춘다.
            - Accept: text/*;q=0.3, text/html;q=0.7, text/html;level=1,
                         text/html;level=2;q=0.4, */*;q=0.5
                
                
                | Media Type | Quality |
                | --- | --- |
                | text/html;level=1 | 1 |
                | text/html | 0.7 |
                | text/plain | 0.3 |
                | image/jpeg | 0.5 |
                | text/html;level=2 | 0.4 |
                | text/html;level=3 | 0.7 |
- Transmission Method
    - 단순 전송(Content-Length)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2071.png)
        
    - 압축 전송(Content-Encoding)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2072.png)
        
    - 분할 전송(Transfer-Encoding)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2073.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2074.png)
        
        - 분할 전송 시에는 Content의 길이를 예상할 수 없기에 헤더에 Content-Length가 포함되면 안 된다.
    - 범위 전송(Range, Content-Range)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2075.png)
        
- General Information
    - From : 유저 에이전트의 이메일 정보
        - 일반적으로 잘 사용되지 않음
        - 검색 엔진 같은 곳에서 주로 사용
        - 요청에서 사용
    - Referer : 이전 웹 페이지 주소
        - 현재 요청된 페이지의 이전 웹 페이지 주소
        - A → B로 이동하는 경우 B를 요청할 때 Referer: A를 포함해서 요청
        - Referer를 사용해서 유입 경로 분석 가능
        - 요청에서 사용
        - Referer는 referrer의 오타
    - User-Agent: 유저 에이전트 애플리케이션 정보
        - user-agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/
        537.36 (KHTML, like Gecko) Chrome/86.0.4240.183 Safari/537.36
        - 클라이언트의 애플리케이션 정보(웹 브라우저 정보, 등등)
        - 통계 정보
        - 어떤 종류의 브라우저에서 장애가 발생하는지 파악 가능
        - 요청에서 사용
    - Server: 요청을 처리하는 오리진 서버(프록시, 캐시가 아닌 요청을 처리하는 마지막 도착지 서버)의 소프트웨어 정보
        - Server: Apache/2.2.22 (Debian)
        - Server: nginx
        - 응답에서 사용
    - Date: 메시지가 생성된 날짜
        - Date: Tue, 15 Nov 1994 18:12:31 GMT
        - 응답에서 사용
- Special Information
    - Host: 요청한 호스트 정보(도메인)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2076.png)
        
        - 요청에서 사용
        - 필수
        - 하나의 서버가 여러 도메인을 처리해야 할 때
        - 하나의 IP 주소에 여러 도메인이 적용되어 있을 때
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2077.png)
            
    - Location: 페이지 리다이렉션
        - 웹 브라우저는 3xx 응답의 결과에 Location 헤더가 있으면, Location 위치로 자동 이동(Redirect)
        - 201 (Created) : Location 값은 요청에 의해 생성된 Resource URI
        - 3xx (Redirection) : Location 값은 요청을 자동으로 Redirection 하기 위한 대상 resource를 가리킨다.
    - Allow: 허용 가능한 HTTP 메서드
        - 405 (Method Not Allowed)에서 응답에 포함해야 한다.
        - Allow: GET, HEAD, PUT
    - Retry-After: 유저 에이전트가 다음 요청을 하기까지 기다려야 하는 시간
        - 503(Service Unavailable) : 서비스가 언제까지 불능인지 알려줄 수 있다.
        - Retry-After: Fri, 31 Dec 1999 23:59:59 GMT (날짜 표기)
        - Retry-After: 120(초단위 표기)
- Authorization
    - Authorization: 클라이언트 인증 정보를 서버에 전달
        - Authorization: Basic xxxxxxxxxxxxxxxx
    - WWW-Authenticate: 리소스 접근시 필요한 인증 방법 정의
        - 리소스 접근시 필요한 인증 방법 정의
        - 401 Unauthorized 응답과 함께 사용
        - WWW-Authenticate: Newauth realm="apps", type=1, title="Login to \"apps\"", Basic realm="simple"
- Cookie
    - Set-Cookie: 서버에서 클라이언트로 쿠키 전달(응답)
    - Cookie: 클라이언트가 서버에서 받은 쿠키를 저장하고, HTTP 요청시 서버로 전달
    - 쿠키 미사용시
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2078.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2079.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2080.png)
        
    - Stateless
        - HTTP는 무상태(Stateless) 프로토콜이다
        - 클라리언트와 서버가 요청과 응답을 주고 받으면 연결이 끊어진다
        - 클라이언트가 다시 요청하면 서버는 이전 요청을 기억하지 못한다
        - 클라이언트와 서버는 서로 상태를 유지하지 않는다.
    - 쿠키 사용시
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2081.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2082.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2083.png)
        
    - 쿠키
        - Ex) set-cookie: sessionId=abcde1234; expires=Sat, 26-Dec-2020 00:00:00 GMT; path=/; domain=.google.com; Secure
        - 사용처
            - 사용자 로그인 세션 관리
            - 광고 정보 트래킹
        - 쿠키 정보는 항상 서버에 전송됨
            - 네트워크 트래픽 추가 유발
            - 최소한의 정보만 사용(세션 id, 인증 토큰)
            - 서버에 전송하지 않고, 웹 브라우저 내부에 데이터를 저장하고 싶으면 웹 스토리지(localStorage, sessionStorage) 참고
        - 보안에 민감한 데이터는 저장하면 안 된다.
    - 쿠키 - 생명주기(Expires, max-age)
        - Set-Cookie: expires=Sat, 26-Dec-2020 04:39:21 GMT
            - 만료일이 되면 쿠키 삭제
        - Set-Cookie: max-age=3600 (3600초)
            - 0이나 음수를 지정하면 쿠키 삭제
        - 세션 쿠키 : 만료 날짜를 생략하면 브라우저 종료시 까지만 유지
        - 영속 쿠키 : 만료 날짜를 입력하면 해당 날짜까지 유지
    - 쿠키 - 도메인(domain)
        - Ex) domain=example.org
        - 명시: 명시한 문서 기준 도메인 + 서브 도메인 포함
            - domain=example.org를 지정해서 쿠키 생성
                - example.org는 물론
                - dev.example.org도 쿠키 접근
        - 생략: 현재 문서 기준 도메인만 적용
            - example.org에서 쿠키를 생성하고 domain 지정을 생략
                - example.org에서만 쿠키 접근 가능
                - dev.example.org는 쿠키 미접근
    - 쿠기 - 경로(Path)
        - Ex) path=/home
        - 이 경로를 포함한 하위 경로 페이지만 쿠키 접근
        - 일반적으로 path=/ 루트로 지정
        - Ex)
            - path=/home 지정
            - /home → 가능
            - /home/level1 → 가능
            - /home/level1/level2 → 가능
            - /hello → 불가능
    - 쿠키 - 보안(Secure, HttpOnly, SameSite)
        - Secure
            - 쿠키는 HTTP, HTTPS를 구분하지 않고 전송
            - Secure를 적용하면 HTTPS인 경우에만 전송
        - HttpOnly
            - XSS 공격 방지
            - 자바스크립트에서 접근 불가(document.cookie)
            - HTTP 전송에만 사용
        - SameSite
            - XSRF 공격 방지
            - 요청 도메인과 쿠키에 설정된 도메인이 같은 경우만 쿠키 전송

# Section 8. HTTP Header 2 - Cache and Conditional Request

- Cache Default Behavior
    - 캐시가 없을 경우
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2084.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2085.png)
        
        - 데이터가 변경되지 않아도 계속 네트워크를 통해서 데이터를 다운로드 받아야 한다.
        - 인터넷 네트워크는 매우 느리고 비싸다
        - 브라우저 로딩 속도가 느리다
        - 느린 사용자 경험
    - 캐시가 적용된 경우
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2086.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2087.png)
        
        - 캐시로 인해 캐시 가능 시간동안 네트워크를 사용하지 않아도 된다
        - 비싼 네트워크 사용량을 줄일 수 있다
        - 브라우저 로딩 속도가 매우 빠르다
        - 빠른 사용자 경험
    - 캐시 시간 초과
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2088.png)
        
        ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2089.png)
        
        - 캐시 유효 시간이 초과하면, 서버를 통해 데이터를 다시 조회하고, 캐시를 갱신한다
        - 이때 다시 네트워크 다운로드가 발생한다.
- Validation headers and conditional requests 1
    - 캐시 시간 초과
        - 캐시 유효 시간이 초과해서 서버에 다시 요청하면 다음 두 가지 상황이 나타난다
            - 서버에서 기존 데이터를 변경
            - 캐시 만료후에도 서버에서 기존 데이터를 변경하지 않음
                - 데이터를 전송하는 대신 저장해 두었던 캐시를 재사용 할 수 있다
                - 단, 클라이언트의 데이터와 서버의 데이터가 같다는 사실을 확인할 수 있는 방법이 필요하다.
                    
                    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2090.png)
                    
                    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2091.png)
                    
                    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2092.png)
                    
                    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2093.png)
                    
                    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2094.png)
                    
                    - HTTP Body 생략으로 비용을 줄일 수 있다.
                    
                    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2095.png)
                    
                    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2096.png)
                    
    - 캐시 유효 시간이 초과해도, 서버의 데이터만 갱신되지 않으면 304 Not Modified + 헤더 메타 정보만 응답한다. (Body는 보내지 않는다)
    - 클라이언트는 서버가 보낸 응답 헤더 정보로 캐시의 메타 정보를 갱신한다
    - 클라이언트는 캐시에 저장되어 있는 데이터를 재활용한다
    - 결과적으로 네트워크 다운로드가 발생하지만, 용량이 적은 헤더 정보만 다운로드하게 된다.
- Validation headers and conditional requests 2
    - 검증 헤더
        - 캐시 데이터와 서버 데이터가 같은지 검증하는 데이터
        - Last-Modified, ETag
    - 조건부 요청 헤더
        - 검증 헤더로 조건에 따른 분기
        - If-Modified-Since: Last-Modified 사용
        - If-None-Match: ETag 사용
        - 조건이 만족하면 200 OK
        - 조건이 만족하지 않으면 304 Not Modified
    - If-Modified-Since: 이후 데이터가 수정되었을 경우
        - 데이터 미변경 예시
            - 캐시 : 2020년 11월 10일 10:00:00 vs 서버 : 2020년 11월 10일 10:00:00
            - 304 Not Modified, 헤더 데이터만 전송(BODY 미포함)
            - 전송 용량 0.1M(헤더 0.1M)
        - 데이터 변경 예시
            - 캐시 : 2020년 11월 10일 10:00:00 vs 서버 : 2020년 11월 10일 11:00:00
            - 200 OK, 모든 데이터 전송(BODY 포함)
            - 전송 용량 1.1M (헤더 0.1M, 바디 1.0M)
    - Last-Modified, If-Modified-Since 단점
        - 1초 미만(0.x초) 단위로 캐시 조정 불가능
        - 날짜 기반의 로직 사용
            - 데이터를 수정해서 날짜가 다르지만, 같은 데이터를 수정해서 데이터 결과가 같은 경우(수정 후 롤백 같은 경우)에도 전체 다시 다운로드
            - 서버에서 별도의 캐시 로직을 관리하고 싶은 경우
                - Ex) 스페이스나 주석처럼 크게 영향이 없는 변경에서 캐시를 유지하고 싶은 경우
    - ETag, If-None-Match
        - ETag(Entity Tag)
        - 캐시용 데이터에 임의의 고유한 버전 이름을 달아둔다.
            - Ex) ETag: “v1.0”, ETag: “a2jiodwjekjl3”
        - 데이터가 변경되면 이 이름을 바꿔서 변경한다(Hash를 다시 생성)
            - Ex) Etag: “aaaaa” → ETag: “bbbbb”
        - ETag만 보내서 같으면 유지하고, 다르면 다시 받는다.
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2097.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2098.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%2099.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%20100.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%20101.png)
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%20102.png)
    
    - 캐시 제어 로직을 서버에서 완전히 관리할 수 있다
    - 클라이언트는 단순히 이 값을 서버에 제공한다.(클라이언트는 캐시 메커니즘을 모른다)
    - Ex)
        - 서버는 배타 오픈 기간인 3일 동안 파일이 변경되어도 ETag를 동일하게 유지
        - 애플리케이션 배포 주기에 맞춰 ETag 모두 갱신
- Cache and conditional request Header
    - 캐시 제어 헤더
        - Cache-Control : 캐시 제어
            - 캐시 지시어(directives)
                - Cache-Control: max-age
                    - 캐시 유효 시간, 초 단위
                - Cache-Control: no-cache
                    - 데이터는 캐시해도 되지만, 항상 origin 서버에 검증하고 사용
                - Cache-Control: no-store
                    - 데이터에 민감한 정보가 있으므로 저장하면 안됨
                    - 메모리에서 사용하고 최대한 빨리 삭제
        - Pragma : 캐시 제어(하위 호환)
            - Pragma: no-cache
            - HTTP 1.0 하위 호환
        - Expires : 캐시 유효 기간(하위 호환)
            - 캐시 만료일 지정
            - expires: Mon, 01 Jan 1990 00:00:00 GMT
            - 캐시 만료일을 정확한 날짜로 지정
            - HTTP 1.0부터 사용
            - 지금은 더 유연한 Cache-Control: max-age 권장
            - Cache-Control: max-age와 함께 사용하면 Expires는 무시
    - 검증 헤더(Validator)
        - ETag: "v1.0", ETag: "asid93jkrh2
        - Last-Modified: Thu, 04 Jun 2020 07:19:24 GMT
    - 조건부 요청 헤더
        - If-Match, If-None-Match: ETag 값 사용
        - If-Modified-Since, If-Unmodified-Since: Last-Modified 값 사용
- Proxy cache
    
    ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%20103.png)
    
    - Cache-Control
        - 캐시 지시어(directives) - 기타
        - Cache-Control: public
            - 응답이 public 캐시에 저장 가능
        - Cache-Control: private
            - 응답이 해당 사용자만을 위한 것. private 캐시에 저장해야 한다. (기본 값)
        - Cache-Control: s-maxage
            - 프록시 캐시에만 적용되는 max-age
        - Age: 60 (HTTP 헤더)
            - origin 서버에서 응답 후 프록시 캐시 내에 머문 시간(초)
- Cache invalidation
    - 캐시 무효화를 위한 기능
        - Cache-Control: no-cache, no-store, must-revalidate
        - Pragma: no-cache
    - Cache-Control: must-revalidate
        - 캐시 만료 후 최초 조회시 origin 서버에 검증해야 한다
        - origin 서버 접근 실패시 반드시 오류가 발생해야 한다 - 504(Gateway Timeout)
        - must-revalidate는 캐시 유효 시간이라면 캐시를 사용한다.
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%20104.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%20105.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%20106.png)
            
            ![Untitled](모든_개발자를_위한_HTTP_웹_기본_지식/Untitled%20107.png)
            

[certificate.pdf](모든_개발자를_위한_HTTP_웹_기본_지식/certificate_(1).pdf)