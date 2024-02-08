# Spring Cloud

# Outline

- Spring Cloud Config는 여러 Application 및 Environments에서 분산 구성을 저장하고 제공하기 위한 Spring의 Client/Server 접근 방식이다.
- 구성 저장소는 Git 버전 제어에 따라 이상적으로 버전이 지정되며 Application Runtime에서 수정할 수 있다.
- Spring Application에 매우 적합하지만 모든 프로그래밍 언어를 실행하는 모든 환경에서 사용할 수 있다.

# Project Setup and Dependencies

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-config-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

- Denpendencies 구성은 위와 같다. 단, Client Project의 경우 spring-boot-starter-security Module은 생략 가능하다.

# **A Config Server Implementation**

- Application의 주요 부분은 Config Class, 특히 @SpringBootApplication이며 자동 config Annotation @EnableConfigServer를 통해 필요한 모든 설정을 가져온다.
    
    ```xml
    @SpringBootApplication
    @EnableConfigServer
    public class ConfigServer {
        
        public static void main(String[] arguments) {
            SpringApplication.run(ConfigServer.class, arguments);
        }
    }
    ```
    
- Server가 수신 대기 중인 Server Port와 버전 제어 구성 Content를 제공하는 Git-url을 구성해야 한다. 후자는 http .ssh 또는 Local File System의 간단한 파일과 같은 Protocol과 함께 사용할 수 있다.
    - 동일한 Config Repository를 가리키는 여러 Config Server Instance를 사용하려는 경우 Repository를 Local 임시 폴더에 복제하도록 Server를 구성할 수 있다. 그러나 2단계 인증을 사용하는 개인 Repository의 경우 다루기가 어려우므로, Local File System에서 복제하고 복사본으로 작업하는 편을 권장한다.
    - Application을 다시 시작할 때마다 비밀번호가 자동으로 생성되지 않도록 application.properties에서 기본 인증에 대한 사용자 이름과 비밀번호를 설정한다.
        
        ```xml
        server.port=8888
        spring.cloud.config.server.git.uri=ssh://localhost/config-repo
        spring.cloud.config.server.git.clone-on-start=true
        spring.security.user.name=root
        spring.security.user.password=s3cr3t
        ```
        

# **The Client Implementation**

- Server를 가져오려면 구성이 application.properties 파일에 있어야 한다.
    
    ```java
    @SpringBootApplication
    @RestController
    public class ConfigClient {
        
        @Value("${user.role}")
        private String role;
    
        public static void main(String[] args) {
            SpringApplication.run(ConfigClient.class, args);
        }
    
        @GetMapping(
          value = "/whoami/{username}",  
          produces = MediaType.TEXT_PLAIN_VALUE)
        public String whoami(@PathVariable("username") String username) {
            return String.format("Hello! 
              You're %s and you'll become a(n) %s...\n", username, role);
        }
    }
    ```
    
    - Application 이름 외에도 application.properties에 active profile과 연결 세부 정보를 넣는다.
        
        ```xml
        spring.application.name=config-client
        spring.profiles.active=development
        spring.config.import=optional:configserver:http://root:s3cr3t@localhost:8888
        ```
        

# **Encryption and Decryption**

- Spring 암호화 및 복호화 기능과 함께 강력한 암호화 키를 사용하려면 JVM에 ‘JCE(Java Cryptohgraphy Extension) Unlimited Strength Jurisdiction Policy Files’가 설치되어 있어야 한다.
- Config Server가 속성 값의 Encryption 및 Decryption을 지원하므로 공용 Repository를 사용자 이름 및 암호화 같은 Sensitive Data의 Repository로 사용할 수 있다. Encryption된 값에는 문자열 [cipher]가 접두사로 추가되며 서버가 대칭 키 또는 키 쌍을 사용하도록 구성된 경우 ‘/encrypt’ 경로에 대한 REST 호출로 생성할 수 있다.
- 해독할 Endpoint도 사용할 수 있다. 두 Endpoint 모두 Application 이름과 현재 프로필에 대한 자리 표시자를 포함하는 경로 ‘/*/[name]/[profile]’를 허용한다.
    
    # CSRF
    
    - Spring Security는 Application으로 전송되는 모든 요청에 대해 CSRF 보호를 활성화하므로 /encrypt 및 /decrpt Endpoint를 사용하려면 CSRF를 비활성화해야 한다.
        
        ```java
        @Configuration
        public class SecurityConfiguration {
        
            @Bean
            public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                http.csrf()
                  .ignoringAntMatchers("/encrypt/**")
                  .ignoringAntMatchers("/decrypt/**");
        
                //...
            }
        }
        ```
        
    
    # **Key Management**
    
    - 기본적으로 Config Server는 대칭 또는 비대칭 방식으로 속성 값을 암호화할 수 있다.
        - 대칭 암호화를 사용하려면 application.properties의 ‘encrypt.key’ 속성을 선택한 비밀 키로 설정하기만 하면 된다. 또는 환경 변수 ENCRYPT_KEY를 전달할 수 있다.
        - 비대칭 암호화의 경우 ‘encrypt.key’를 PEM 인코딩 문자열 값으로 설정하거나 사용할 Key Repository를 구성할 수 있다.
        - 먼저 java keytool을 사용하여 RSA 키 쌍을 포함하여 새 키 저장소를 생성하는 것과 함께 후자의 옵션을 선택한다.
            
            ```java
            $> keytool -genkeypair -alias config-server-key \
                   -keyalg RSA -keysize 4096 -sigalg SHA512withRSA \
                   -dname 'CN=Config Server,OU=Spring Cloud,O=Baeldung' \
                   -keypass my-k34-s3cr3t -keystore config-server.jks \
                   -storepass my-s70r3-s3cr3t
            ```
            
        - 그 뒤 생성된 Key Repository를 Server의 Application.properties에 추가 하고 다시 실행한다.
            
            ```java
            encrypt.keyStore.location=classpath:/config-server.jks
            encrypt.keyStore.password=my-s70r3-s3cr3t
            encrypt.keyStore.alias=config-server-key
            encrypt.keyStore.secret=my-k34-s3cr3t
            ```
            
        - 다음으로 암호화 Endpoint를 Query하고 Repository의 구성에 값으로 응답을 추가한다.
            
            ```java
            $> export PASSWORD=$(curl -X POST --data-urlencode d3v3L \
                   http://root:s3cr3t@localhost:8888/encrypt)
            $> echo "user.password={cipher}$PASSWORD" >> config-client-development.properties
            $> git commit -am 'Added encrypted password'
            $> curl -X POST http://root:s3cr3t@localhost:8888/refresh
            ```
            
        - 설정 확인은 ConfigClient Class를 수정 하고 Client를 다시 시작한다.
            
            ```java
            @SpringBootApplication
            @RestController
            public class ConfigClient {
            
                ...
                
                @Value("${user.password}")
                private String password;
            
                ...
                public String whoami(@PathVariable("username") String username) {
                    return String.format("Hello! 
                      You're %s and you'll become a(n) %s, " +
                      "but only if your password is '%s'!\n", 
                      username, role, password);
                }
            }
            ```
            
    
    # **Using Multiple Keys**
    
    - 암호화 및 해독에 여러 키를 사용하려는 경우 [cipher] 접두사와 BASE64 인코딩 속성 값 사이에 [name:value] 형식으로 또 다른 접두사를 추가할 수 있다.
    - Config Server는 기본적으로 [secret:my-crypto-secret] 또는 [key:my-key-alias]와 같은 접두사를 이해한다. 후자의 옵션은 application.properties에 구성된 key Repository가 필요하다. 이 Key Repository는 일치하는 key Alias을 검색한다.
    - Key Repository가 없는 시나리오의 경우 검색을 처리하고 각 키에 대해 TextEncryptor -Object를 반환하는 TextEncryptorLocator 유형의 @Bean을 구현해야 한다.
    
    # **Serving Encrypted Properties**
    
    - Server Side 암호화를 비활성화하고 속성 값의 암호 해독을 Local에서 처리하려면 application.properties에 다음을 입력할 수 있다.
        
        ```java
        spring.cloud.config.server.encrypt.enabled=false
        ```
        
        - 또한 다른 모든 ‘encrypt.*’ 속성을 삭제하여 REST Endpoint를 비활성화 할 수 있다.