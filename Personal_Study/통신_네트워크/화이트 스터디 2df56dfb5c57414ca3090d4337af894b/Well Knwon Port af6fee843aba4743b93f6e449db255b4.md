# Well Knwon Port

> Well-Knwon Prot ? 특정한 쓰임새를 위해서 IANA에서 할당한 TCP 및 UDP 포트 번호의 일련번호
> 

### 7, Echo

- TCP, UDP에서 작동한다.
- 소스로부터 수신된 모든 데이터를 다시 전송하여 작동하는 디버깅 및 측정 프로토콜이다.

### 20, File Transfer Protocol (FTP) - Data Transfer

- 연결을 위한 기본 데이터 포트
- 클라이언트와 서버 간 데이터 파일을 전송하는 데 사용된다.
- 데이터 채널 및 데이터 전송에 사용된다.

### 21, File Transfer Protocol (FTP) - Command Control

- 연결을 위한 기본 데이터 포트
- 제어 정보를 전달하고 두 컴퓨터 또는 호스트 간의 연결을 설정하는 데 사용된다.
- 20번 포트와 21번 포트가 모두 열려 있어야 성공적인 파일 전송이 가능하다.

### 22, Secure Shell (SSH)

- 네트워크로 연결된 장치 간에 보안 통신을 제공하는 암호화 네트워크 프로토콜이다.
- 암호화된 연결을 설정하여 사용자가 원격으로 시스템에 안전하게 액세스하고 관리할 수 있도록 한다.
- SSH는 Unix, Linux, MacOS를 포함한 다양한 OS에서 널리 사용된다.
- 주로 시스템의 보안 원격 관리에 사용된다.

### 23, Telnet

- Telnet 프로토콜에서 원격 컴퓨터와의 연결을 설정하는 데 사용된다.
    - Telnet : 다양한 통신 시스템과 네트워킹 장치에 대한 원격 액세스를 제공하는 플랫폼 독립적인 도구
    - 사용자가 파일과 폴더를 보고, 생성하고, 편집하고, 삭제할 수 있는 명령줄 액세스를 제공한다.

### 25, SMTP(Siimple Mail Transfer Protocol)

- 전자 메일 메시지를 수신자에게 전달할 때 송신 서버와 수신 서버 간의 통신을 활성화하는 데 사용되는 기본 SMTP 포트. OG로 현재는 거의 사용되지 않는다.
- 이메일을 보내는 데 주로 사용되며, 네트워크 서비스의 원격 관리와 같은 다른 목적으로도 사용된다.

### 53, DNS(Domain Name System)

- 사람이 읽을 수 있는 호스트 이름을 숫자 IP 주소로 해석한다.
- Malware를 포함한 대부분의 네트워크 소프트웨어는 연결을 설정하기 전에 DNS를 사용하여 도메인을 IP 주소로 확인한다.
- 영역 전송에 Port 53을 사용하여 DNS 데이터베이스와 서버 간의 일관성을 유지한다.
- UDP 프로토콜은 클라이언트가 DNS 서버에 쿼리를 보낼 때 사용된다.

### 67(68), DHCP(Dynamic Host Configuration Protocol

- DHCP 서버에서 사용하는 UDP 포트
- 67 포트를 사용하여 장치가 네트워크에 참여할 때 장치에 구성 정보를 제공한다.
    - 정보에는 IP 주소, 게이트웨이 설정 및 DNS 서버가 포함된다.
    - DHCP 서버는 네트워크의 클라이언트와 서버 간의 통신을 시작한다.
- DHCP 클라이언트는 UDP 포트 68을 사용하여 해당 포트 번호로 전송된 메시지에만 응답한다.
- DHCP는 조직 네트워크의 각 호스트에 IP 주소를 동적으로 할당하는 데 사용된다.

### 69, TFTP(Trivial File Transfer Protocol)

- 파일 전송을 위한 간단하고 연결이 없는 프로토콜
- 작고 구현하기 쉽도록 설계되었으며 보다 강력한 파일 전송 프로토콜의 고급 기능이 대부분 부족하다.
- TFTP는 UDP 포트 69를 사용하며 인증이 필요하지 않다.
- 클라이언트는 RFC 1350에 설명된 데이터그램 형식을 사용하여 서버에서 읽고 쓴다.
- 전송 자체는 임시 포트에서 완전히 이루어진다.

### 80, HTTP(Hypertext Transfer Protocol)

- 암호화되지 않은 웹 페이지를 보내고 받는 데 사용되는 기본 네트워크 포
- 웹 페이지를 검색하는 데 사용되는 안전하지 않은 전송 프로토콜
- 암호화되지 않는다.
- 기본적으로 열려 있으며 대부분의 컴퓨터, 서버 및 라우터 제조업체에서는 기본적으로 포트 80을 통해 웹 서버와 브라우저 간의 HTTP 통신을 활성화한다.

### 88, Kerberos (Network Authenitcaion Systrem)

> Kerberos는 비밀 키 암호화를 사용하여 사용자를 인증하고, 서비스 액세스를 위한 티켓을 발행하고, 통신의 개인정보 보호를 보장하는 컴퓨터 네트워크 인증 프로토콜이다.
> 
> 
> 주로 UDP 프로토콜이지만 대규모 Kerberos 티켓의 경우 TCP로 대체된다.
> 
- KDC1에 대해 Kerberos가 사용하는 기본 포트.
- Kerberos 클라이언트는 포트 88에서 UDP 및 TCP 패킷을 보내고 Kerberos 서버로부터 응답을 받아야 한다.
- Kerneros는 여러 비밀 키, 제 3자 인증 및 암호화를 사용하여 보안 확인 프로토콜을 만든다.
- 비밀번호는 네트워크를 통해 전송되지 않으며, 비밀 키는 암호화되므로 공격자가 사용자나 서비스를 가장하는 것이 어렵다.

### 102, ISO-TSAP (ISO Transport Service Access Point (TSAP) Class 0 Protocol

- ISO-TSAP 클래스 0 프로토콜. 두 호스트 컴퓨터 사이에 사용된다.
- TSAP 서버는 TCP 포트 102에서 수신 대기한다. TSAP 클라이언트가 포트에 성공적으로 연결되면 프로토콜이 시작된다.

### 110, POP3 (Post Office Protocol, version 3)

- POP3용 기본 포트로, 이메일 클라이언트는 POP3 서버에 연결하여 이메일을 검색할 수 있다.
- 원래 한 대의 컴퓨터에서만 사용하도록 설계된 오래된 프로토콜로, 단방향 이메일 동기화만 지원하므로 사용자는 서버에서 클라이언트로 이메일을 다운로드할 수 있다.

### 119, NNTP (Network News Transfer Protocol)

> NNTP : 뉴스 서버 간에 뉴스 기사를 전송하고 사용자가 기사를 읽고 게시할 수 있도록 하는 애플리케이션 프로토콜
> 
- NNTP 서버는 포트 119를 사용하여 다른 NNTP 서버와 뉴스 기사를 푸시하고 가져온다.
- 뉴스 읽기 및 쓰기 클라이언트도 포트 119를 사용하여 뉴스 서버와 통신한다.

### 123, NTP (Network Time Protocol)

> NTP는 교차 알고리즘을 사용하여 정확한 시간 서버를 찾고 네트워크 대기 시간의 영향을 최소화한다.
> 
> 
> 이를 통해 시스템의 시계를 1~50ms 이내로 설정할 수 있다.
> 
- NTP에서 컴퓨터를 동일한 UTC 시간으로 동기화하는 데 사용된다.
- NTP는 UDP 기반 서비스이며 NTP 서버는 포트 123을 사용하여 서로 및 NTP 클라이언트와 통신한다.
- NTP 클라이언트는 1023 이상의 임의 포트를 사용한다.

### 135, Microsoft EPMAP (End Point Mapper)

- 포트 135는 RPC(Remote Procedure Call) Endpoint 매퍼 서비스이다.
    - DNS, DHCP, WINS와 같은 서비스를 원격으로 관리하기 위한 포트.
    - DCE/RPC 로케이터 서비스라고도 한다.
- 이를 통해 다른 시스템은 시스템에서 어떤 서비스를 사용할 수 있는지, 그리고 해당 서비스를 찾을 수 있는 포트를 식별할 수 있다.
- RPC 클라이언트-서버 통신에 사용된다.
- WIndows DCE-RPC 스택의 취약점으로 인해 원격 사용자가 RPC 서비스를 비활성화할 수 있다.

### 137(138, 139), NBNS(Net BIOS Name Service)

> NBNS : 로컬 호스트 파일이나 DNS를 요구하지 않고 네트워크에서 이름을 확인하는 프로토콜
> 
- LAN(Local Area Network) 내에서 컴퓨터와 장치 간의 통신을 가능하게 하는 레거시 네트워크 프로토콜이다.
    - 모든 최신 버전의 Windows에서 파일 및 인쇄 공유에 사용된다.
- NetBIOS 서비스를 활성화하면 인터넷상의 모든 사람이 파일 및 프린터와 같은 공유 리소스에 액세스할 수 있다.

### 143 IMAP4 (Internet Message Access Protocol)

> IMAP : 사용자의 이메일 프로그램이 메일 서버에 액세스할 수 있도록 하는 API이다.
> 
- IMAP의 기본 포트. 암호화 기능을 제공하지 않으므로 안전하지 않다.
    - 암호화는 TSL/SSL 암호화를 사용하는 993 포트에서 이루어진다.
- 암호화되지 않은 연결이나 기회적 TLS(STARTTLS) 암호화 연결에 사용된다.

### 161(162), SNMP (Simple Network Management Protocol)

> SNMP : 라우터 및 방화벽과 같은 장치 및 응용 프로그램에서 원격 모니터링 응용 프로그램과 관리 및 로깅 정보를 전달하는 데 사용된다.
> 
- 네트워크 장치의 SNMP 쿼리를 위한 기본 포트
- 161과 162 포트를 모두 사용하여 메시지와 명령을 보낸다.
- SNMP 관리자는 지정된 SNMP 포트를 통해 SNMP 에이전트와 통신한다.
- SNMP 메시지 전송은 UDP를 통해 수행된다.

### 194, IRC (Internet Relay Chat)

> IRC : 개인이 실시간 온라인 대화를 할 수 있도록 특정 프로토콜을 사용하는 인터넷 서버 네트워크.
> 
- 네트워크를 통해 채팅방 스타일 서비스를 제공하기 위해 IRC 프로토콜에서 사용된다.

### 389, LDAP(Lightweight Directory Access Protocol)

> LDAP : TCP 또는 UDP를 통해 포트 389를 사용하는 애플리케이션 계층 프로토콜. MS Active Directory 및 일부 이메일 프로그램에서 연락처 정보를 조회하는 데 사용된다.
> 
- 암호화되지 않은 LDAP 통신을 위한 기본 포트. 디렉터리 관련 데이터 교환에 사용된다.

### 443, HTTPS (Hypertext Transfer Protocol Secure)

- HTTP의 보안 버전인 HTTPS의 표준 포트.
- 도청자가 데이터를 가로채는 것을 방지하기 위해 웹 사이트 및 기타 온라인 서비스에서 사용된다.
- HTTPS 서비스에 전역적으로 사용된다. HTTPS 트래픽은 SSL/TLS라는 암호화 알고리즘을 사용하여 암호화된다.
- 웹 브라우저와 웹사이트 간에 전송되는 민감한 정보를 보호한다.
- 트래픽을 올바른 경로로 전달하고 장치가 요청 중인 서비스 유형을 식별하는 데 도움이 된다.

### 464, Kerberos (Kerberos Change/Set password)

- Kerberos 비밀번호 변경에 사용되는 포트
- 이전 비밀번호 변경 프로토콜인 Kerberos 5 비밀번호 변경 서비스에 사용된다.

### 465, SMTP over TLS/SSL, SSM

- 더 이상 사용되지 않는 포트.
- IETP(Internet Engineering Task Force)는 포트 465를 사용하지 않으며 권장되지 않는다.
- 이전에는 보안 SMTP(SMTPS)에 사용되었다. 암시적 TLS 및 SSL과 같은 프로토콜에 할당되었다.
- 현재 SMTP는 SMTP Secure(SMTPS)를 사용하여 587을 사용한다.
- 대부분의 SMTP 서버는 포트 465를 사용한 연결을 차단한다.

### 587, SMTP (Email message submission)

- 최신 웹에서 SMTP 전송을 위한 기본 포트.
- SMTP Secure(SMTPS)를 사용하여 암호화된 이메일 전송에 사용된다.
- STARTTLS를 사용하여 SMTP 메시지를 암호화하는 데 자주 사용된다.
- 이를 통해 이메일 클라이언트는 메일 서버에 TLS 연결을 통해 연결을 업그레이드하도록 요청하여 보안 연결을 설정할 수 있다.
- 일반적으로 이메일 전송을 위한 보안 연결을 제공하는 인증 및 암호화가 필요하다.
- 일반적으로 보내는 메일을 제출하는 데 사용된다.

### 593, Microsoft DCOM

- Microsoft Exchange Server 및 DCOM(Distributed Component Object Model) 서비스에서 자주 사용되는 HTTP(Hypertext Transfer Protocol)를 통한 RPC(Remote Procedure Call)

### 636, LDAP(Lightweight Directory Access Protocol) over TLS/SSL

- 보안 LDAP 연결을 위해 사용된다.
- 이 연결을 통해 사용자는 네트워크 장치 및 조직 데이터에 액세스할 수 있다.

### 691, MS Exchange (MS Exchange Routing)

- Microsoft Exchange Routing Engine(RESvc)에서 라우팅 링크 상태 정보를 수신하는 데 사용된다.
- Exchange는 이 정보를 사용하여 메시지를 라우팅하고 라우팅 테이블을 업데이트한다.

### 902, VMware Server (VMware ESXi)

- 호스트와 가상 센터 간의 통신을 위해 VMware에서 사용하는 포트.
- 기본 암호화 포트이기도 하다.
- 다음의 용도로도 사용된다.
    - 내부 하이퍼바이저 통신
    - ESXi - vCenter 통신
    - 클라이언트 콘솔에 연결
    - vCenter Server에 연결하기 위한 Update Manager
    - ESXi 호스트에 연결하기 위한 Update Manager
    - HTTP를 통해 호스트 패치 다운로드에 액세스하는 ESXi 호스트
    - 호스트 업그레이드 파일을 푸시하는 업데이트 관리자.

### 989, FTP over SSL (FTPS Protocol (data), TFP over TLS/SSL)

- FTPS의 데이터 연결에 사용된다.
- IANA(Internet Assigned Numbers Authority)는 공식적으로 990 포트를 FTPS 제어 채널 포트로 지정하고 989 포트를 FTPS 데이터 채널 포트로 지정한다.
- 일반적으로 제어 연결에 포트 990을 사용하고 암시적 보안 하에서 데이터 연결에 포트 989를 사용한다.

### 990, FTP over SSL (FTPS Protocol (control), TFP over TLS/SSL)

- 암시적 보안 FTP 세션의 기본 포트 번호.
- 보호된 포트 또는 TLSPORT 라고도 한다.

### 993, IMAP4 over SSL (Internet Message Access Protocol over TLS/SSL)

- IMAP의 보안 포트이며 SSL 암호화에 사용된다.
- 암호화된 IMAP 이메일 통신에 사용된다.

### 995, POP3 over SSL (Post Office Protocol 3 over TLS/SSL

- POP3용 암호화된 포트.
- SSL/TLS 암호화를 사용한다.