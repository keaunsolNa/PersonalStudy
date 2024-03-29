# Docker

![Untitled](Docker/Untitled.png)

# 정의

- Docker는 애플리케이션을 신속하게 구축, 테스트 및 배포할 수 있는 소프트웨어 플랫폼이다.
    - OS 수준 가상화를 사용하는 PaaS 제품 세트다.
    - 소프트웨어를 컨테이너라는 표준화된 유닛으로 패키징하며, 컨테이너에는 라이브러리, 시스템 도구, 코드, 런타임 등 소프트웨어를 실행하는 데 필요한 모든 것이 포함되어 있다.
    - Docker를 사용할 경우 환경에 구애받지 않고 애플리케이션을 신속하게 배포 및 확장할 수 있으며, 코드가 문제 없이 실행될 것임을 확신할 수 있다.
- Docker는 컨테이너를 패키지화하고 프로비저닝하고 실행한다.
    - 컨테이너 기술은 OS를 통해 사용할 수 있다.
    - Docker는 OS 커널의 리소스 격리를 사용하여 동일한 OS에서 여러 컨테이너를 실행한다.
    - 이는 물리적 하드웨어 리소스의 추상화된 계층 위에 실행 가능한 코드로 전체 OS를 캡슐화하는 가상 머신과는 다른 방식이다.

# 관련 용어 및 구성요소

![Untitled](Docker/Untitled%201.png)

### Docker 파일

- 모든 Docker 컨테이너는 Docker 컨테이너 이미지를 빌드하는 방법에 대한 지침이 포함된 간단한 텍스트 파일로 시작된다.
- DockerFile은 Docker 이미지 생성 프로세스를 자동화한다.
- 이는 기본적으로 Docker 엔진이 이미지를 어셈블하기 위해 실행할 명령줄 인터페이스(CLI) 명령 목록이다.
- Docker 명령 목록은 방대하지만 표준화되어 있다.
- Docker 작업은 콘텐츠, 인프라 또는 기타 환경 변수에 관계없이 동일하게 작동한다

### Dokcer 이미지

- Docker 이미지에는 실행 가능한 애플리케이션 소스 코드는 물론 애플리케이션 코드가 컨테이너로 실행되는 데 필요한 모든 도구, 라이브러리 및 종속성이 포함되어 있다.
- Docker 이미지를 실행하면 컨테이너의 하나의 인스턴스가 된다.
- Docker 이미지를 처음부터 빌드하는 것이 가능하지만 대부분의 개발자는 공통 리포지토리에서 해당 이미지를 가져온다.
- 단일 기본 이미지에서 여러 Docker 이미지를 생성할 수 있으며 스택의 공통성을 공유한다
- Docker 이미지는 레이어로 구성되며 각 레이어는 이미지 버전에 해당한다.
- 개발자가 이미지를 변경할 때마다 새로운 최상위 레이어가 생성되고 이 최상위 레이어는 이미지의 현재 버전으로 이전 최상위 레이어를 대체한다
- 이전 레이어는 롤백을 위해 저장되거나 다른 프로젝트에서 재사용된다.
- Docker 이미지에서 컨테이너가 생성될 때마다 컨테이너 계층이라는 또 다른 새 계층이 생성된다.
- 파일 추가 또는 삭제 등 컨테이너에 대한 변경 사항은 컨테이너 레이어에만 저장되며 컨테이너가 실행되는 동안에만 존재한다
- 이 반복적인 이미지 생성 프로세스는 여러 라이브 컨테이너 인스턴스가 단일 기본 이미지에서 실행될 수 있고 그렇게 할 때 공통 스택을 활용하므로 전반적인 효율성을 높일 수 있다.

### Dokcer 컨테이너

- Docker 컨테이너는 Docker 이미지의 실시간 실행 인스턴스다.
- Docker 이미지는 읽기 전용 파일인 반면, 컨테이너는 수명이 길고 일시적이며 실행 가능한 콘텐츠다.
- 사용자는 이들과 상호 작용할 수 있으며 관리자는 Dokcer 명령을 사용하여 설정과 조건을 조정할 수 있다.

### Dokcer 허브

- Docker Hub는 Docker 이미지의 공개 저장소이다.
- 상용 소프트웨어 공급업체, 오픈 소스 프로젝트, 개인 개발자로부터 소스를 얻은 100,000개 이상의 컨테이너 이미지를 보유하고 있다.
- Docker, Inc. 에서 생성한 이미지, Docker Trusted Registry에 속한 인증 이미지 및 기타 수천 개의 이미지가 포함된다.
- 모든 Docker Hub 사용자는 마음대로 이미지를 공유할 수 있다. 또한 Docker 파일 시스템에서 사전 정의된 기본 이미지를 다운로드하여 컨테이너화 프로젝트의 시작점으로 사용할 수도 있다

### Dokcer Desktop

- Docker Desktop은 Docker 엔진, Docker CLI 클라이언트, Docker Compose, Jubernetes 등을 포함하는 Mac 또는 WInodws 용 애플리케이션이다.
- Docker Hub에 대한 액세스도 포함된다.

### Dokcer Daemon

- Docker Daemon은 클라이언트의 명령을 사용하여 Docker 이미지를 생성하고 관리하는 서비스다.
- 기본적으로 Docker 데몬은 Docker 구현의 제어 센터 역할을 한다.
- Docker 데몬이 실행되는 서버를 Docker 호스트라고 한다.

### Dokcer Registry

- Docker Registry는 Docker 이미지를 위한 확장 가능한 오픈 소스 스토리지 및 배포 시스템이다.
- Registry를 사용하면 식별용 태그를 사용하여 저장소의 이미지 버전을 추적할 수 있다.
- 이는 버전 제어 도구인 git을 사용하여 수행된다.

# 주요 사용 사례

- 소프트웨어의 지속적인 배포
    - Docker 기술과 DevOps 방식을 사용, 기존의 모놀리식 애플리케이션과 달리 컨테이너화된 애플리케이션을 몇 초 안에 배포할 수 있다.
    - 대규모의 CI/CD 파이프라인의 일부인 컨테이너를 사용하면 애플리케이션 코드에 대한 업데이트 또는 변경 사항이 신속하게 구현되고 배포된다.
- 마이크로서비스 기반 아키텍쳐 구축
    - 마이크로서비스 기반 아키텍처가 기존의 모놀리식 애플리케이션보다 더 유리한 경우 Docker는 이 아키텍처를 구축하는 프로세스에 이상적이다.
    - 개발자는 자체 컨테이너 내에 여러 마이크로서비스를 구축하고 배포한다.
    - 그 뒤 Docker Swarm과 같은 컨테이너 조정 도구를 사용하여 이를 통합하여 전체 소프트웨어 애플리케이션을 조립한다
- 레거시 애플리케이션 마이그레이션
    - 기존 레거시 소프트웨어 애플리케이션을 마이그레이션하고 싶을 때, 개발 팀은 Docker를 사용하여 앱을 컨테이너화 된 인프라로 전환 가능하다
- 하이브라이드 클라우드 및 멀티 클라우드 애플리케이션 활성화
    - Docker 컨테이너는 온프레미스에 배포하거나 클라우드 컴퓨팅 기술을 사용하거나 동일한 방식으로 작동한다.
    - 따라서 Docker를 사용하면 애플리케이션을 다양한 클라우드 공급업체의 프로덕션 및 테스트 환경으로 쉽게 이동할 수 있다.

# 장단점

- 장점
    - 높은 수준의 이식성으로 다양한 호스트를 통해 컨테이너를 등록하고 공유할 수 있다
    - 자원 사용을 줄인다
    - VM에 비해 배포가 더 빠르다
- 단점
    - 기업에서 가능한 컨테이너 수는 효율적 관리가 어려울 수 있다
    - 컨테이너 사용은 세분화된 가상 호스팅에서 애플리케이션 구성 요소 및 리소스의 오케스트레이션으로 진화하고 있다.
        - 결과적으로 수백 개의 임시 컨테이너가 포함될 수 있는 구성 요소화된 애플리케이션의 배포 및 상호 연결이 주요 장애물이 되고 있다.
- 보안 이슈
    - Docker 컨테이너는 논리적 격리가 수행되지만, 여전히 호스트의 OS를 공유한다.
    - 기본 OS의 공격이나 결함은 해당 OS 위에서 실행되는 모든 컨테이너를 잠재적으로 손상시킬 수 있다.
    - 취약점에는 액세스 및 구너한 부여, 컨테이너 이미지, 컨테이너 간 네트워크 트래픽이 포함될 수 있다
    - Docker 이미지는 기본적으로 호스트에 대한 루트 액세스를 유지할 수 있지만 이는 종종 타사 공급업체의 패키지에서 전달된다.

# 구현

- javabucks 프로젝트 도커 이미지
    
    ![Untitled](Docker/Untitled%202.png)
    
    - 순서대로 mysql 서버
    - nginx 서버
    - frontend node 서버
    - backend springboot 서버
- Docker-compose.yml
    
    ```yaml
    version: "3"
    services:
      frontend:
        build:
          dockerfile: Dockerfile.dev
          context: ./frontend
        volumes:
          - /app/node_modules
          - ./frontend:/app
        stdin_open: true
    
      nginx:
        restart: always
        build:
          dockerfile: Dockerfile
          context: ./nginx
        ports:
          - "3000:80"
    
      backend:
        build:
          dockerfile: Dockerfile.dev
          context: ./backend
        container_name: app_backend
        volumes:
          - /app/node_modules
          - ./backend:/app
        environment:
          -  WDS_SOCKET_PORT=5694
          
      mysql:
        build: ./mysql
        restart: unless-stopped
        container_name: app_mysql
        ports:
          - "3306:3306"
        volumes:
          - ./mysql/mysql_data:/var/lib/mysql
          - ./mysql/sqls/:/docker-entrypoint-initdb.d/
        environment:
          MYSQL_ROOT_PASSWORD: temppassword
          MYSQL_DATABASE: lists
    ```
    
- backend- Dockerfile
    
    ```yaml
    FROM node:14.14.0-alpine
    
    WORKDIR /app
    
    COPY ./package.json ./
    
    RUN npm install
    
    COPY . .
    
    CMD ["npm", "run", "start"]
    ```
    
- frontend- Dockerfile
    
    ```yaml
    FROM node:16-alpine as builder
    WORKDIR /app
    COPY ./package.json ./
    RUN npm install
    COPY . .
    RUN npm run build
    
    FROM nginx
    EXPOSE 3000 
    COPY ./nginx/default.conf /etc/nginx/conf.d/default.conf
    COPY --from=builder /app/build  /usr/share/nginx/html
    ```
    
- nginx - Dockerfile
    
    ```yaml
    FROM nginx
    COPY ./default.conf  /etc/nginx/conf.d/default.conf
    ```
    
- mysql - Dockerfile
    
    ```yaml
    FROM mysql:5.7
    
    ADD ./my.cnf /etc/mysql/conf.d/my.cnf
    ```