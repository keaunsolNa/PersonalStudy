# JDBC 교육

<aside>
💡 JDBC는 다양한 DMBS와 통신할 때 서로 다른 방식의 컨버팅 과정을 거치지 않고, JDBC DRIVER를 통해 컨버팅하기 위한 것이 그 본질이다.

</aside>

# 1. ORIENTATION

- JDBC란?
    - Java DataBase Connectivity): 자바에서 데이터베이스에 접근할 수 있게 해주는 Programming API
        
        ![Untitled](JDBC_교육/Untitled.png)
        
        ![Untitled](JDBC_교육/Untitled%201.png)
        
    - 수업에서는 Oracle JDBC Driver를 이용한다.
    - DriverManager를 통해 Connection을 만든 뒤, Connection을 통해 Statement를 만든다. 이후 Statement를 통해 db와정보를 주고 받는다.
    - 비유하자면, Connection은 도로, Statement는 트럭. 주고 받는 정보(Query)가 트럭의 수하물.
    
- JDBC의 절차
    
    ![Untitled](JDBC_교육/Untitled%202.png)
    
    ![Untitled](JDBC_교육/Untitled%203.png)
    
    ![Untitled](JDBC_교육/Untitled%204.png)
    
    ![Untitled](JDBC_교육/Untitled%205.png)
    
    ![Untitled](JDBC_교육/Untitled%206.png)
    

# 2. JDBC 개요 및 연결

- DriverManager, Connection절차
    - Connection 레퍼런스 변수를 선언한다. (추후 try-catch를 위해)
    Connection con = null;
    - 사용할 드라이버를 등록한다. (사용할 DBMS 인지)
    Class.forname(”oracle.jdbc.driver.OracleDriver”);
    → 목적지 (jdbc.driver.OracleDriver)설정
    - DriverManager를 이용해 Connection 인스턴스를 생성한다. 
    → ip주소와 port 번호, 계정의 아이디와 비밀번호를 connection을 통해 연결한다.
    → con = DriverManager.getConnection("jdbc:oracle:thin:@localhost:1521:xe", 
                                                                    “C##EMPLOYEE", "EMPLOYEE");
    → 앞서 생성한 con 인스턴스의 할당 과정. Drivermanager의 getConnection 내장 메소드를 통해 jdbc:oracle의 ip주소(localhost)와 포트번호(1521),
    사용할 dbms의 계정 이름(C##EMPLOYEE)와 비밀번호(EMPLOYEE)를 입력한다.
    → 목적지의 주소(ip), 호수(port) 이름(username)과 비밀번호(password) 입력
    - 이후, try catch 구문과 close를 통해 닫아준다.
    - 총 구문은 아래와 같다.
        
        ```java
        Connection con = null;
        		
        		try {
        			
        			Class.forName("oracle.jdbc.driver.OracleDriver");
        			
        			con = DriverManager.getConnection("jdbc:oracle:thin:@localhost:1521:xe", 
        											  "C##EMPLOYEE", "EMPLOYEE");	
        			
        			System.out.println("con: " + con);
        		} catch (ClassNotFoundException e) {
        			e.printStackTrace();
        		} catch (SQLException e) {
        			e.printStackTrace();
        		} finally {
        			if(con != null) {
        				try {
        					con.close();
        				} catch (SQLException e) {
        				e.printStackTrace();
        			}
        		}
        	}
        ```
        
    - driver, url, user, password의 레퍼런스 변수를 생성 한다.
        
        Connection의 레퍼런스 변수를 생성한다.
        드라이버 등록(레퍼런스 변수를 활용해서)
        Connection의 인스턴스 생성 (레퍼런스 변수를 활용해서)
        이후 출력과 try-catch를 통한 예외처리, close처리.
        
        ```java
        String driver = "oracle.jdbc.driver.OracleDriver";
        		String url = "jdbc:oracle:thin:@localhost:1521:xe";
        		String user = "C##EMPLOYEE";
        		String password = "EMPLOYEE";
        		
        		Connection con = null;
        		
        		try {
        			Class.forName(driver);
        			
        			con = DriverManager.getConnection(url, user, password);
        			
        			System.out.println("con: " + con);
        		} catch (ClassNotFoundException e) {
        			e.printStackTrace();
        		} catch (SQLException e) {
        			e.printStackTrace();
        		} finally {
        			if(con != null);{
        				try {
        					con.close();
        				} catch (SQLException e) {
        					e.printStackTrace();
        				}
        			}
        		}
        ```
        
    - driver, url, user, password 정보를 외부 파일에 별도로 저장하고, 해당 파일을 FileReader를 통해 불러오는 과정을 통해서도 Connection이 가능하다. 
    Properties의 인스턴스 생성
    Connection의 레퍼런스 변수 생성
        
        Properties 내장 메소드 load, FileReader를 통해 외부에 저장된 driver, url, user, password 호출. 
        호출된 driver, url, user, password의 스트링 타입 인스턴스 생성
        Connection의 인스턴스 생성 (호출한 url, user, password 활용해서)
        이후 try-catch를 통해 예외처리와 close 처리
        
        ```java
        package com.greedy.section01.connection;
        
        import java.io.FileNotFoundException;
        import java.io.FileReader;
        import java.io.IOException;
        import java.sql.Connection;
        import java.sql.DriverManager;
        import java.sql.SQLException;
        import java.util.Properties;
        
        public class Application3 {
        	public static void main(String[] args) {
        
        		Properties prop = new Properties();
        		
        		Connection con = null;
        		
        		try {
        			prop.load(new FileReader("src/com/greedy/section01/connection/jdbc-config.properties"));
        			
        			System.out.println(prop);
        			
        			String driver = prop.getProperty("driver");
        			String url = prop.getProperty("url");
        			String user = prop.getProperty("user");
        			String password = prop.getProperty("password");
        			
        			Class.forName(driver);
        			
        			con = DriverManager.getConnection(url, user, password);
        			
        			System.out.println("con: " + con);
        			
        		} catch(FileNotFoundException e) {
        			e.printStackTrace();
        		} catch (IOException e) {
        			e.printStackTrace();
        		} catch (ClassNotFoundException e) {
        			e.printStackTrace();
        		} catch (SQLException e) {
        			e.printStackTrace();
        		} finally{
        			if(con != null) {
        				try {
        					con.close();
        				} catch (SQLException e) {
        					e.printStackTrace();
        				}
        			}
        		}
        	}
        }
        ```
        
    - 앞서 만들었던 과정들을 하나의 클래스의 메소드 안에 담은 뒤, 해당 클래스의 메소드를 호출하는 식으로 객체 호출, close등을 편리하게 할 수 있다.
    단, 이 때 RETURN값에 null 값이 아닌 인스턴스명을 넣어줘야 한다. (안 그러면 null-point-exception 발생)
        
        우선 Connection의 레퍼런스 변수와 Properties의 인스턴스를 생성 한 뒤,
        Properties를 이용, 외부 파일에 있는 driver, url, user, password 호출
        이후 driver, url String 자료형 인스턴스 생성
        
        드라이버 등록
        
        try- catch 후 (close는 하지 않는다!)
         return 값으로 Connection 레퍼런스 변수 입력
        
        ```java
        public static Connection getconnection() {
        
        		Connection con = null;
        		
        		Properties prop = new Properties();
        		
        		try {
        			prop.load(new FileReader("config/connection-info.properties"));
        			
        			System.out.println(prop);
        			String driver = prop.getProperty("driver");
        			String url = prop.getProperty("url");
        			
        			Class.forName(driver);
        			
        			con = DriverManager.getConnection(url, prop);
        			
        			System.out.println("getConnection의 con: " + con);
        		} catch (IOException e) {
        			e.printStackTrace();
        		} catch (ClassNotFoundException e) {
        			e.printStackTrace();
        		} catch (SQLException e) {
        			e.printStackTrace();
        		} 
        		
        		return con;
        	}
        
        public static void close(Connection con) {
        		try {
        			if(con != null && !con.isClosed()) {		
        				con.close();
        			}
        		} catch (SQLException e) {
        			e.printStackTrace();
        		}
        	}
        ```
        
    - close 역시 메소드를 만든 뒤 호출하는 방식으로 사용할 수 있다.
- Connection, Statement, ResultSet
    - Connection을 통해 연결할 DB 데이터를 설정한 뒤, 
    Statement를 통해 쿼리문을 저장하고 실행하는 기능을 만든다.
    ResultSet을 통해 Select 쿼리 실행 후 돌아오는 결과 집합(Result Set)을 받아 준다.
        
        ```java
        package com.greedy.section01.statement;
        
        import static com.greedy.common.JDBCTemplate.getConnection;
        import static com.greedy.common.JDBCTemplate.close;
        import java.sql.Connection;
        import java.sql.ResultSet;
        import java.sql.SQLException;
        import java.sql.Statement;
        
        public class Application1 {
        
        	public static void main(String[] args) {
        
        		Connection con = getConnection();
        		
        		Statement stmt = null;
        		
        		ResultSet rset = null;
        		
        		
        		try {
        			
        			stmt = con.createStatement();
        			
        			rset = stmt.executeQuery("SELECT A.EMP_ID, A.EMP_NAME 사원명 FROM EMPLOYEE A WHERE EMP_ID = '201'");
        			
        			while(rset.next()) {
        				System.out.println(rset.getString("EMP_ID") + ", " + rset.getString("사원명"));		
        			}
        		} catch (SQLException e1) {
        			e1.printStackTrace();
        		} finally {
        			close(rset);
        			close(stmt);
        			close(con);
        		}				
        	}
        }
        ```
        
    - 흐름은 위와 같다. 우선 Connection의 인스턴스를 외부 class의 getConnection method를 통해 생성한다.
    - 이후 Statment, ResultSet의 레퍼런스 변수를 선언한다.
    - 이후 Connection 인스턴스로 Statment의 인스턴스를 생성한다.
    - 이후 executeQuery() method로 쿼리문을 실행하고, 그 결과를 ResultSet에 반환한다.
    - 이후, while문을 활용, rset.next()를 통해 결과값으로 받은 쿼리문을 println과 결합, 하나하나 출력한다.   (Iterator 때 사용했던 방식)
    - 이후 예외처리와 외부 method close를 호출하여 닫아주면 끝.
- PreparedStatement와 Connection과의 차이점
    - PreparedStatement는 준비된 Connection으로 생각할 수 있다. 값 부분에 ?(Placeholder)를 사용할 수 있으며, 해당 기능을 통해 sqlinjection을 방지할 수 있다. 
    → ‘’을 사용할 수 없게 막아둠으로서, or을 이용한 해킹 시도를 방지하는 것.
        
        ```java
        String query = "SELECT A.* FROM EMPLOYEE A WHERE EMP_ID = '" + empId + "'";
        -- Connecion 의 경우
        
        String query = "SELECT EMP_ID, EMP_NAME, SALARY FROM EMPLOYEE WHERE EMP_ID = ?";
        -- PreparedStatement 의 경우
        
        String query = "SELECT * FROM EMPLOYEE WHERE EMP_NAME LIKE '%' || ? || '%'";
        -- PreparedStatement의 ''는 위와 같은 방식으로 사용된다.
        ```
        
    - 그 외에는 큰 차이점은 없다. 선언 시  Statement가 아닌 PreparedStatement를 사용하는 정도.
- sqlinjection
    - 해킹 방법으로, 10대 보안 위협에 속한다.
    - 특정 정보를 입력받아 처리할 때, Connection을 사용 시 가지는 구조적인 약점을 이용,  필드변수를
        
        ```java
        private static String empName = "' OR 1=1 AND EMP_ID = '203";
        ```
        
    - 위와 같은 방식으로 항상 true가 나오게 설정함으로서 로그인같은 상황에서 두 개의 값 중 하나만 알아도 방비를 무력화시킬 수 있다.
    - 이를 방지하기 위한 방법은 간단한데, PreparedStatement를 사용하면 된다.
- log 파일 추가
    - java_Project 폴더에 properties를 통해 Source탭으로 진입. 로그 파일을 저장해둔 폴더를 add Folder를 통해 추가하여 적용한다. 이후 Lib에 추가한 log4jdbc를 config의 계정 연결 용 properties에(connection-info.properties) 추가하여 log 등록을 할 수 있다.

# 3. CRUD

- Insert
    - ResultSet이 아닌,  int result = 0; 을 통해 결과값을 insert가 됐다는 1, 안 됐다는 0으로 받는다.
    - 결과 구문은 
    result = pstmt.executeUpdate();
    로, executeQuery가 아닌 executeUpdate()로 진행하며, 해당 결과는 1과 0으로 (int형) 반환된다.
    - java에서 DML 작업을 할 때, close 단계에서 commit이 진행된다.
        
        ```java
        package com.greedy.section01.insert;
        
        import static com.greedy.common.JDBCTemplate.getConnection;
        import static com.greedy.common.JDBCTemplate.close;
        
        import java.io.FileInputStream;
        import java.io.IOException;
        import java.sql.Connection;
        import java.sql.PreparedStatement;
        import java.sql.SQLException;
        import java.util.Properties;
        
        public class Application1 {
        
        	public static void main(String[] args) {
        		Connection con = getConnection();
        		
        		PreparedStatement pstmt = null;
        		int result = 0;			// DML을 진행하면 결과가 ResultSet이 아닌 int가 반환된다.
        		
        		Properties prop = new Properties();
        		
        		try {
        			prop.loadFromXML(new FileInputStream("mapper/menu-query.xml"));
        			String query = prop.getProperty("insertMenu");
        			
        			System.out.println("menu insert 쿼리: " + query);
        			
        			pstmt = con.prepareStatement(query);
        			pstmt.setString(1, "초코릿샤브샤브");
        			pstmt.setInt(2, 10000000);
        			pstmt.setInt(3, 7);
        			pstmt.setString(4, "Y");
        			
        			result = pstmt.executeUpdate();			// DML 쿼리를 수행할 때는 executeUpdate()이며 결과는 int(DML 작업이 이루어진 행의 갯수)다.
        			
        			
        		} catch (IOException e) {
        			e.printStackTrace();
        		} catch (SQLException e) {
        			e.printStackTrace();
        		} finally {
        			close(pstmt);
        			close(con);								// 트랜잭션인 commit이 발생함.
        		}
        		
        		System.out.println("result : " + result);
        	}
        
        }
        ```
        
    - Properties를 통해 XML문서의 SQL문을 쿼리로 지정한다.
    - 이후 PreparedStatement의 값으로 connection을 통해 앞서 지정한 query값을 지정한다.
    - 이후 preparedStatement의 레퍼런스 변수를 이용해서 각 placeholder에 값들을 set을 통해 지정한다.
    - 이후 executeUpdate를 통해 Insert 작업이 이루어졌는지 여부를 0과 1로 반환하는 result 변수 값을 지정한다.
- Update
    - 전체적인 흐름은 Insert와 유사하다. DTO 클래스에 컬럼들을 자료형에 맞춰 변수화 해 두고, set을 통해 XML파일에 지정해 둔 SQL문의 Placeholder에 값을 넣는다. 이후 해당 값을 다시 get을 통해 꺼내온 뒤 PreparedStatement의 레퍼런스 변수에 값을 지정하는 것.
        
        ```java
        package com.greedy.section02.update;
        
        import static com.greedy.common.JDBCTemplate.getConnection;
        import static com.greedy.common.JDBCTemplate.close;
        
        import java.io.FileInputStream;
        import java.io.IOException;
        import java.sql.Connection;
        import java.sql.PreparedStatement;
        import java.sql.SQLException;
        import java.util.Properties;
        import java.util.Scanner;
        
        import com.greedy.model.dto.MenuDTO;
        
        public class Application1 {
        
        	public static void main(String[] args) {
        		Scanner sc = new Scanner(System.in);
        		System.out.print("변경 할 메뉴 번호를 입력하세요: ");
        		int menuCode = sc.nextInt();
        		System.out.print("변경 할 메뉴의 이름을 입력하세요: ");
        		sc.nextLine();
        		String menuName = sc.nextLine();
        		System.out.print("변경 할 메뉴의 가격을 입력하세요: ");
        		int menuPrice = sc.nextInt();
        		System.out.print("변경 할 메뉴의 카테고리를 입력하세요(4~12)");
        		int categoryCode = sc.nextInt();
        		System.out.print("메뉴 판매 여부를 입력하세요(Y/N): ");
        		sc.nextLine();
        		String orderableStatus = sc.nextLine().toUpperCase();
        		
        		MenuDTO changedMenu = new MenuDTO();
        		changedMenu.setCode(menuCode);
        		changedMenu.setPrice(menuPrice);
        		changedMenu.setName(menuName);
        		changedMenu.setCategoryCode(categoryCode);
        		changedMenu.setOrderableStatus(orderableStatus);
        
        		/* -------------------------------------------------------------- */
        
        		Connection con = getConnection();
        		PreparedStatement pstmt =null;
        		int result = 0;
        		
        		Properties prop = new Properties();
        		
        		try {
        			prop.loadFromXML(new FileInputStream("mapper/menu-query.xml"));
        			String query = prop.getProperty("updateMenu");
        			
        //			System.out.println("update을 위해 가져온 쿼리: " + query);
        			
        			pstmt = con.prepareStatement(query);
        			pstmt.setString(1, changedMenu.getName());
        			pstmt.setInt(2, changedMenu.getPrice());
        			pstmt.setInt(3, changedMenu.getCategoryCode());
        			pstmt.setString(4, changedMenu.getOrderableStatus());
        			pstmt.setInt(5, changedMenu.getCode());
        			
        			result = pstmt.executeUpdate();
        			
        		} catch (IOException e) {
        			e.printStackTrace();
        		} catch (SQLException e) {
        			e.printStackTrace();
        		} finally {
        			close(pstmt);
        			close(con);
        		}
        		
        		if(result > 0) {
        			System.out.println("메뉴 수정 성공!");
        		} else {
        			System.out.println("메뉴 수정 실패!");
        		}
        	}
        
        }
        ```
        
    - Scanner를 통해 유저가 입력한 값을 변수지정한 뒤, 해당 변수를 DTO클래스의 set 값으로 지정한다.
    - 이후 Properties를 통해 XML 문서의 SQL문을 불러온다.
    - 이후 유저가 입력한 값을 get을 통해 DTO 클래스에서 가져온 뒤, 해당 값을 XML 문서의 SQL문서의 set 값으로 지정한다. (PreparedStatement를 통해)
    - 이후 executeUpdate를 통해 업데이트가 반영되었는지 반환값을 0과 1로 확인한다.
    - 이후 if문을 통해 0과 1의 결과문을 출력한다.
- Delete
    - 역시 흐름은 동일하다.
        
        ```java
        package com.greedy.section03.delete;
        
        import static com.greedy.common.JDBCTemplate.getConnection;
        import static com.greedy.common.JDBCTemplate.close;
        
        import java.io.FileInputStream;
        import java.io.IOException;
        import java.sql.Connection;
        import java.sql.PreparedStatement;
        import java.sql.SQLException;
        import java.util.Properties;
        import java.util.Scanner;
        
        public class Application1 {
        	public static void main(String[] args) {
        		Scanner sc = new Scanner(System.in);
        		System.out.println("삭제 할 메뉴 번호를 입력하세요: ");
        		int menuCode = sc.nextInt();
        		
        		/* ------------------------------------------------------ */
        		
        		Connection con = getConnection();
        		PreparedStatement pstmt = null;
        		int result = 0;
        		
        		Properties prop = new Properties();
        		
        		try {
        			prop.loadFromXML(new FileInputStream("mapper/menu-query.xml"));
        			String query = prop.getProperty("deleteMenu");
        			
        //			System.out.println("메뉴 delete용 쿼리: " + query);
        			
        			pstmt = con.prepareStatement(query);
        			pstmt.setInt(1, menuCode);
        			
        			result = pstmt.executeUpdate();
        			
        		} catch (IOException e) {
        			e.printStackTrace();
        		} catch (SQLException e) {
        			e.printStackTrace();
        		} finally {
        			close(pstmt);
        			close(con);
        		}
        	
        		if(result > 0) {
        			System.out.println("메뉴 삭제 성공!");
        		} else {
        			System.out.println("메뉴 삭제 실패!");
        		}
        		
        		
        	}
        
        }
        ```
        
    - 위 구문에서의 delete는 pk번호 하나로 delete 구문을 실행하기에 DTO 클래스는 생략한다.
    - Scanner api를 통해 유저 입력 번호를 int 변수로 지정한 뒤
    - Properties를 통해 XML 문서의 delete sql문 query 값으로 불러온다.
    - 이후 Preparestatement 를 통해 query 값을 받는다.
    - 이후 executeUpdate를 통해 반환값을 result 변수에 담는다.
    - 이후 0과 1로 if문을 시행해 결과 출력.

# 4. 계층과 계층 분할

- 계층구조 (MVC MODEL2)
    
    ![KakaoTalk_20220408_181121995.png](JDBC_교육/KakaoTalk_20220408_181121995.png)
    
    - 정의와 개요
        - Model(Service, dao, dto) View Controller
        
        ![Untitled](JDBC_교육/Untitled%207.png)
        
        - View: 출력. 화면에 보여지는 단계 (프론트)
        - Controller: 사용자가 입력한 정보를 하나로 묶어(DTO, MAP) 두는 단계
        - Service: Connection: Controller에서 받은 정보를 객체를 생성/반납 하며, 트랜잭션을 제어하는 단계
        - DAO: Statement, PrepareStatement를 통해 query를 생성/반납 하는 단계
        - DB: DataBase. 정보 처리 단계.
        - → MVC MODEL1에서는 view, Controller, service를 하나의 단계로 묶는 구조.
        - MVC 역시 하나의 디자인 패턴이다.
    - 5 계층
        - View
            - 사용자에게 보여지는 (화면단) 부분의 코드
            - 타 계층(Service)으로부터 받아온 정보를 출력하고, 화면단에 보여질 문장 등을 Print method를 통해 출력하는 등의 역할을 한다.
            - 필드 영역에 Controller 생성자를 만들고 메소드를 호출한다.
            - 트랜잭션에 따른 Service 영역의 최종 결과값에 따른 화면단 영역.
            - 다만, Controller에서 처리할 수 없는 가공처리(반복으로 인한 누적값이 필요한 경우) 는 View 영역에서 처리한다.
            - 사용자가 입력한 정보를 DTO와 MAP에 담아 Controller로 전달한다.
        - Controller
            - 사용자가 입력한 정보를 파라미터 형태(DTO, MAP)로 전달받은 뒤, 전달받은 값을 검증한다.
            - 정보의 가공은 본래 Service영역에서 맡는 일이었으나, Controller 영역에서 맡는 것이 추세.
            - Service의 생성자를 만들고 메소드를 호출한다.
            - 수행 결과를 반환 받아 사용자에게 보여줄 뷰를 결정한다.
            → 로그인 시 성공화면과 실패 화면과 같은 형식
            - 뷰에 필요한 데이터를 전달한다.
        - Service (Transaction)
            - DB의 정보를 조회하기 위해 연결(Connection)을 담당하는 계층.
            - Connection 객체를 생성하고, 반환(close)한다. 매개변수로 Connection인스턴스의 레퍼런스 변수를 던진다.
            - DAO 생성자를 필드 영역에서 생성한다.
            - Connection과 Controller에서 받은 객체를 생성/반납 하며, 트랜잭션을 제어한다.
            - 최종 결과 값을 반환한다. (트랜잭션의 결과값(commit, rollback))
                - 트랜잭션(Transaction)
                    - 다른 말로 논리적 작업 단위(logical unit of work)라고도 한다.
                    - Connection의 내장 메소드인 setAutoCommit은 기본 값 true로 설정되어 있으며, finally를 통해 Connection을 close했을 때 commit 작업이 내부적으로 실행된다.
                    - 이 때 작업은 하나의 논리적 작업 단위(트랜잭션)로 이루어지며, 각각의 작업마다 commit이 자동으로 이루어진다. (따라서, autoCommit이 true일 때, 두 개의 dml작업 중 하나가 false여도 true인 작업은 자동으로 commit, db에 적용된다. )
                    - 이와 같은 방식은 여러 개의 DML작업이 유기적으로 연결되어 있을 때 문제가 발생될 여지가 있다. 따라서 setAutoCommit의 값을 fasle로 설정한 뒤, if문을 통해 트랜잭션 단위를 기준으로  commit이 이루어지게 변경, 하나의 트랜잭션이 모두 true일 때 commit이 이루어지도록 변경할 필요가 있다.
                    - 유기적인 연결은 Trigger를 생각하자. JDBC의 autoCommit이 true라면, trigger의 전제조건(Before)이 false임에도 trigger가 실행될 수 있다.
            - ! Connection은 반드시 지역변수로 선언해야 한다.
        - DAO(Data Access Objects**)**
            - 데이터베이스에 관련된 CRUD 작업을 전문적으로 담당 하는 객체.
            - 단일책임의 원칙을 위해 DB에 접근하는 객체들을 따로 분류하는 디자인 패턴이라고 생각하자.
            - 별도의 class 안에 독립된 method들로 Statement와 PrepareStatement를 이용 해  DB로부터 받아온 결과 집합(ResultSet, Result)을 반환값으로 리턴한다.
        - DB
            - Data Base. Oracle 등의 프로그램을 통해 데이터를 저장해두는 계층.
            - JDBC는 Driver를 통해 DB로부터 정보를 가져온다.
- 계층 분할과 외부 파일
    - DTO
        - Data Transfer Object
        - DB의 데이터 단위(보통 컬럼 단위)를 private 로 캡슐화 하여 객체화 하는 계층 분할 단위.
        - 계층 간 데이터 교환을 위한 목적으로 생성한다.
        - JDBC에서 DTO를 이용한 정보 교환의 방식
            - JDBC에서도 DTO를 이용, set/get을 통해 정보를 불러올 수 있다.
                
                ```java
                package com.greedy.section01.statement;
                
                import static com.greedy.common.JDBCTemplate.getConnection;
                import static com.greedy.common.JDBCTemplate.close;
                
                import java.sql.Connection;
                import java.sql.ResultSet;
                import java.sql.SQLException;
                import java.sql.Statement;
                import java.util.Scanner;
                
                import com.greedy.model.dto.EmployeeDTO;
                
                public class Application4 {
                	public static void main(String[] args) {
                
                		Connection con = getConnection();
                		Statement stmt = null;
                		ResultSet rset = null;
                		
                		EmployeeDTO selectedEmp = null;
                		
                		Scanner sc = new Scanner(System.in);
                		System.out.println("조회 하려는 사번을 입력해 주세요ㅣ ");
                		String empId = sc.nextLine();
                		
                		String query = "SELECT A.* FROM EMPLOYEE A WHERE EMP_ID = '" + empId + "'";
                		
                		try {
                			stmt = con.createStatement();
                			rset = stmt.executeQuery(query);
                			
                			if(rset.next()) {
                //				System.out.println(rset.getString("EMP_NAME"));		// 한 줄의 출력문으로 현재까지 문제가 없이 동작하는지 확인
                				
                				selectedEmp = new EmployeeDTO();
                				
                				selectedEmp.setEmpId(rset.getString("EMP_ID"));
                				selectedEmp.setEmpName(rset.getString("EMP_NAME"));
                				selectedEmp.setEmpNo(rset.getString("EMP_no"));
                				selectedEmp.setEmail(rset.getString("EMAIL"));
                				selectedEmp.setPhone(rset.getString("PHONE"));
                				selectedEmp.setDeptCode(rset.getString("DEPT_CODE"));
                				selectedEmp.setJobCode(rset.getString("JOB_CODE"));
                				selectedEmp.setSalLevel(rset.getString("SAL_LEVEL"));
                				selectedEmp.setSalary(rset.getInt("SALARY"));
                				selectedEmp.setBonus(rset.getDouble("BONUS"));
                				selectedEmp.setManagerId(rset.getString("MANAGER_ID"));
                				selectedEmp.setHireDate(rset.getDate("HIRE_DATE"));
                				selectedEmp.setEntDate(rset.getDate("ENT_DATE"));
                				selectedEmp.setEntYn(rset.getString("ENT_YN"));
                			}
                				
                		} catch (SQLException e) {
                			e.printStackTrace();
                		} finally {
                			close(rset);
                			close(stmt);
                			close(con);
                		}
                		
                		System.out.println("selectedEmp: "  + selectedEmp);
                	}
                
                }
                ```
                
            - Connection, Statement, ResultSet은 기존과 동일하다.
            - EmployeeDTO(className) selectedEmp(VariableName) = null;을 통해 초기화 진행한다.
            - 이후 반복문을 통해 (단일 행일 경우 if문 사용해도 무방) DTOVarName.setFiledVarName(ResultSet VarName.getString(”COLUMN NAME”)); 의 형식으로 각각의 값을 set 한다.
            - 이후 변수 이름으로 출력.
            - 출력할 컬럼이 여러 개인 경우, ArrayList를 이용할 수 있다. 전체적인 틀은 위와 크게 다르지 않지만, ArrayList의 선언과 초기화 위치에 주의하자. 출력은 ArrayList를 사용할 떄와 동일하게, 4가지 방법 (for문, for-each문, iterator, toString)을 이용할 수 있다.
                
                ```java
                public static void main(String[] args) {
                		Connection con = getConnection();
                		Statement stmt = null;
                		ResultSet rset = null;
                		
                		/* 한 행의 정보를 담을 DTO */
                		EmployeeDTO row = null;
                		
                		/* 여러 DTO를 하나의 인스턴스로 묶기 위한 List */
                		List<EmployeeDTO> empList = null;
                		
                		String query = "SELECT * FROM EMPLOYEE";
                		
                		try {
                			stmt = con.createStatement();
                			rset = stmt.executeQuery(query);
                			
                			empList = new ArrayList<>();			// 전체 행(DTO 인스턴스)을 담을 컬렉션
                			while(rset.next()) {
                				row = new EmployeeDTO();			// 한 행을 담을 DTO 인스턴스
                				
                				row.setEmpId(rset.getString("EMP_ID"));
                				--------------------------------------
                				row.setEntYn(rset.getString("ENT_YN"));
                				
                				empList.add(row);					// 한 행을 컬렉션에 추가하자.
                			}
                		} catch (SQLException e) {
                			e.printStackTrace();
                		} finally {
                			close(rset);
                			close(stmt);
                			close(con);
                		}
                		
                		Iterator<EmployeeDTO> iter = empList.iterator();
                		while(iter.hasNext()) {
                			System.out.println(iter.next());
                		}
                ```
                
    - Library(lib)
        - ojdbc 파일, log 파일 등 JDBC에 필요한 외부 파일을 지정해 둔 뒤, Classpath를 통해 연결하기 위한 계층 분할 단위.
        - 외부 소스와 유사하다.
        - Library 다운로드 및 연결
            - [https://mvnrepository.com/](https://mvnrepository.com/) 방문하여 원하는 라이브러리 검색 (ojbdc)
            - jar파일로 다운로드 후
            - eclipse에서 Navigator 메뉴를 통해 workspace에 새로운 폴더 (lib)를 만든다.
            - 해당 폴더에 다운로드 받은 라이브러리 파일 (압축 상태로) 복사 붙여넣기.
            - workspace 프로젝트 폴더에 properties - java Build Path - Libraries 진입
            - Classpath에 add jars를 누른 뒤 복사 붙여넣기 한 압축 파일 (jar 파일) 선택.
            - Classpath에 해당 파일 확인되고, Navigator에서 해당 파일 좌측 하단으로 책 모양 그림 생성되면 Library 설치 및 연결 성공.
    - config
        - Connection을 위한 driver, url, user, password의 값을 저장한 파일. 이후 template를 통해 dirver 연결을 위한 properties의 value값.
        - 그 외에도 설정 파일을 저장하기 위한 계층 분할 단위.
    - dbscript
        - 프로젝트에서 사용할 DB의 스크립트 파일을 저장하기 위한 계층 분할 단위
    - common(Template)
        - Connection, close, commit, rollback등의 필수요소를 메소드 형식으로 모아두는 계층. 이후 메소드를 결과값으로 대입하거나(Connection), import를 통해 편리하게 사용하기 위한 게층 분할
    - mapper
        - XML 문서를 담아두기 위한 계층 분할.
        - XML
            - CRUD를 비롯한 DB SQL 명령어 쿼리를 별도의 파일로 지정하여 저장 해 두는 계층 분할 단위. Properties를 이용, key값으로 지정 해둔 query문을 불러오는 방식으로 사용된다.
                
                ```java
                public static void main(String[] args) {
                
                		Properties prop = new Properties();
                		prop.setProperty("keyString", "valueString");
                		
                		try {
                			prop.storeToXML(
                					new FileOutputStream("src/com/greedy/section02/preparedStatement/employee=query.xml"), "title");
                		} catch (FileNotFoundException e) {
                			e.printStackTrace();
                		} catch (IOException e) {
                			e.printStackTrace();
                		}
                	}
                ```
                
            - 위와 같은 방식으로 XML 파일을 생성하거나, 이클립스의 New 파일을 통해 XML파일을 생성 한 뒤 기존의 내용을 붙여넣는 방식으로 XML 파일을 만든다.
                
                ```java
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
                <properties>
                    <comment>title</comment>
                    <!--  <entry key="keyString">valueString</entry> -->
                    
                    <!--  단어로 해당 단어를 포함한 이름의 사원을 조회하는 쿼리 -->
                    <entry key="selectEmpByWord">
                	    SELECT
                	    	   A.*	
                	      FROM EMPLOYEE A
                	     WHERE A.EMP_NAME LIKE '%' || ? || '%' 
                    </entry>
                </properties>
                ```
                
            - <entry> </entry> 사이에 사용할 쿼리의 내용을 입력한다. 이후 해당 파일의 selectEmpByWord를 key값으로, value 값인 쿼리 내용을 불러오는 형태를 가진다.
                
                ```java
                prop.loadFromXML(
                					new FileInputStream("src/com/greedy/section02/preparedStatement/employee=query.xml"));
                			
                			String query = prop.getProperty("selectEmpByWord");
                ```
                
            - 이후 properties 인스턴스의 레퍼런스 변수인 prop의 내장 메소드인 loadFromXML을 통해 XML 파일을 불러 온 뒤, 
            prop의 내장 메소드 getProperty를 이용, key값인 sql문의 이름을 넣음으로서 value값인 SQL문 전체를 불러온 뒤 query로 지정한다.
            - XML 서식 생성
                - window - preferences - xml - xmlcatalog - user specified Entries  - add - 
                Location에 
                chap03-crud-lecture-source/mapper/properties.dtd
                key에 
                http://java.sun.com/dtd/properties.dtd 복사 -  key type System ID로 변경
                - 이후 new - xml 파일에서 next 통해 create file using a DTO or XML Schema file - Select XML Catalog entry의 KEY에서 기존에 생성한 XML 클래스 지정하여 생성 가능.
- MVC MODEL2 Code
    - (Main method)
        - MVC MODEL2에서 main method는 View 영역을 호출하는 것 외에는 달리 역할이 없다. 이후 WAS 서버인 톰캣이 해당 역할을 담당하게 될 것. 현재로서는 어플리케이션을 키고 끄는 스위치 정도의 역할.
            
            ```java
            package com.greedy.section01.run;
            
            import com.greedy.section01.view.OrderMenu;
            
            public class Application {
            
            	public static void main(String[] args) {
            
            		OrderMenu orderMenu = new OrderMenu();
            		orderMenu.displayMainMenu();
            		
            	}
            
            }
            ```
            
    - View Layer
        - 사용자에게 보여지는 화면을 담당하는 역할. 현재는 Java로 작업했지만, 이후에는 HTML이 해당 역할을 담당하게 된다.  Controller 계층을 호출한다.
            
            ```java
            package com.greedy.section01.view;
            
            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.Scanner;
            
            import com.greedy.section01.controller.OrderController;
            import com.greedy.section01.model.dto.CategoryDTO;
            import com.greedy.section01.model.dto.MenuDTO;
            import com.greedy.section01.model.dto.OrderMenuDTO;
            
            public class OrderMenu {
            	
            	private OrderController orderController = new OrderController();
            	
            	public void displayMainMenu() {
            		
            		List<OrderMenuDTO> orderMenuList = new ArrayList<>();
            		int totalOrderPrice = 0;
            		
            		Scanner sc = new Scanner(System.in);
            		
            		boolean flag = true;
            		do {
            			System.out.println("======== 음식 주문 프로그램 ========");
            			
            			List<CategoryDTO> categoryList = orderController.selectAllCategory();
            			for(CategoryDTO cate : categoryList) {
            				System.out.println(cate);
            			}
            			
            			System.out.println("===============================");
            			System.out.print("주문하실 카테고리 종류의 이름을 입력해 주세요ㅣ");
            			String inputCategory = sc.nextLine();
            			
            			System.out.println("===========주문 가능 메뉴===========");		
            			
            			List<MenuDTO> menuList = orderController.selectmenuBy(inputCategory, categoryList);
            			for(MenuDTO menu : menuList) {
            				System.out.println(menu);
            			}
            			
            			System.out.print("주문하실 메뉴를 선택해 주세요: ");
            			String inputMenu = sc.nextLine();		
            			
            			int menuCode = 0;
            			int menuPrice = 0;
            			for(int i = 0; i < menuList.size(); i++) {
            				MenuDTO menu = menuList.get(i);
            				if(menu.getName().equals(inputMenu));
            				menuCode = menu.getCode();
            				menuPrice = menu.getPrice();
            			}
            			
            			System.out.println("주문하실 수량을 입력하세요: ");
            			int orderAmount = sc.nextInt();
            			
            			totalOrderPrice += menuPrice * orderAmount;
            			
            			OrderMenuDTO orderMenu = new OrderMenuDTO();
            			orderMenu.setMenuCode(menuCode);
            			orderMenu.setAmount(orderAmount);
            			
            			orderMenuList.add(orderMenu);
            			
            			sc.nextLine();
            			String isContinue = "";
            			while(true) {
            				System.out.println("계속 주문하시겠습니까? (예/아니오): ");
            				isContinue = sc.nextLine();
            				if("예".equals(isContinue)) {
            					break;
            				} else if("아니오".equals(isContinue)) {
            					flag = false;
            					break;
            				}
            			}
            			
            		} while(flag);
            	
            		Map<String, Object> requestMap = new HashMap<>();
            		requestMap.put("totalOrderPrice", totalOrderPrice);
            		requestMap.put("orderMenuList", orderMenuList);
            		
            		orderController.registOrder(requestMap);
            			
            			
            	}
            	
            
            }
            ```
            
    - Controller Layer
        - View로부터 전달받은 정보를 검증 및 가공한다.
            
            ```java
            package com.greedy.section01.controller;
            
            import java.text.SimpleDateFormat;
            import java.util.List;
            import java.util.Map;
            
            import com.greedy.section01.model.dto.CategoryDTO;
            import com.greedy.section01.model.dto.MenuDTO;
            import com.greedy.section01.model.dto.OrderDTO;
            import com.greedy.section01.model.dto.OrderMenuDTO;
            import com.greedy.section01.model.service.OrderService;
            import com.greedy.section01.view.ResultView;
            
            public class OrderController {
            	
            	private OrderService orderService = new OrderService();
            
            	public List<CategoryDTO> selectAllCategory() {
            		return orderService.selectAllCategory();
            	}
            
            	public List<MenuDTO> selectmenuBy(String inputCategory, List<CategoryDTO> categoryList) {
            		int categoryCode = 0;
            		for(int i = 0; i < categoryList.size(); i++) {
            			CategoryDTO category = categoryList.get(i);
            			if(category.getName().equals(inputCategory)) {
            				categoryCode = category.getCode();
            				break;
            			}
            		}
            		
            		return orderService.selectMenuBy(categoryCode);
            	}
            
            	public void registOrder(Map<String, Object> requestMap) {
            	
            		int totalorderPrice = (Integer)requestMap.get("totalOrderPrice");
            		List<OrderMenuDTO> orderMenuList = (List<OrderMenuDTO>)requestMap.get("orderMenuList");
            		
            		java.util.Date orderTime = new java.util.Date();
            		SimpleDateFormat dateFormat = new SimpleDateFormat("yy/MM/dd");
            		SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
            		String date = dateFormat.format(orderTime);
            		String time = timeFormat.format(orderTime);		
            	
            		OrderDTO order = new OrderDTO();
            		order.setDate(date);
            		order.setTime(time);
            		order.setTotalOrderPrice(totalorderPrice);
            		order.setOrderMenuList(orderMenuList);
            		
            		int result = orderService.registOrder(order);	
            
            		ResultView resultView = new ResultView();
            		if(result >0) {
            			resultView.success(orderMenuList.size());
            		} else {
            			resultView.failed();
            		}				
            	}
            }
            ```
            
    - Service Layer
        - DB와의 연결(Connection)을 담당하며, 트랜잭션을 제어한다.
            
            ```java
            package com.greedy.section01.model.service;
            
            import static com.greedy.common.JDBCTemplate.close;
            import static com.greedy.common.JDBCTemplate.getConnection;
            import static com.greedy.common.JDBCTemplate.commit;
            import static com.greedy.common.JDBCTemplate.rollback;
            
            import java.sql.Connection;
            import java.util.List;
            
            import com.greedy.section01.model.dao.OrderDAO;
            import com.greedy.section01.model.dto.CategoryDTO;
            import com.greedy.section01.model.dto.MenuDTO;
            import com.greedy.section01.model.dto.OrderDTO;
            import com.greedy.section01.model.dto.OrderMenuDTO;
            
            public class OrderService {
            	
            	private OrderDAO orderDAO = new OrderDAO();
            
            	public List<CategoryDTO> selectAllCategory() {
            		Connection con = getConnection();
            		
            		List<CategoryDTO> categoryList = orderDAO.selectAllCategory(con);
            		
            		close(con);
            		
            		return categoryList;
            	}
            
            	public List<MenuDTO> selectMenuBy(int categoryCode) {
            		
            		Connection con = getConnection();
            		
            		List<MenuDTO> menuList = orderDAO.selectMenuBy(con, categoryCode);
            		
            		close(con);
            		
            		return menuList;
            	}
            
            	public int registOrder(OrderDTO order) {
            		
            		Connection con = getConnection();
            		
            		int result = 0;
            		
            		int orderResult = orderDAO.registOrder(con, order);
            		
            		int orderCode = orderDAO.selectLastOrderCode(con);
            
            		List<OrderMenuDTO> orderMenuList = order.getOrderMenuList();
            		for(int i = 0; i <orderMenuList.size(); i++) {
            			OrderMenuDTO orderMenu = orderMenuList.get(i);
            			orderMenu.setOrderCode(orderCode);
            		}
            		
            		int orderMenuResult = 0;
            		for(int i =0; i <orderMenuList.size(); i++) {
            			OrderMenuDTO orderMenu = orderMenuList.get(i);
            			orderMenuResult += orderDAO.registOrderMenu(con, orderMenu);
            		}
            		
            		if(orderResult > 0 && orderMenuResult == orderMenuList.size()) {
            			commit(con);
            			result = 1;
            		} else {
            			rollback(con);
            		}
            
            		close(con);
            		
            		return result;
            	}
            }
            ```
            
    - DAO Layer
        - DB와 직접 관련된 CRUD 작업을 담당한다.
            
            ```java
            package com.greedy.section01.model.dao;
            
            import static com.greedy.common.JDBCTemplate.close;
            
            import java.io.FileInputStream;
            import java.io.IOException;
            import java.sql.Connection;
            import java.sql.PreparedStatement;
            import java.sql.ResultSet;
            import java.sql.SQLException;
            import java.sql.Statement;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Properties;
            
            import com.greedy.section01.model.dto.CategoryDTO;
            import com.greedy.section01.model.dto.MenuDTO;
            import com.greedy.section01.model.dto.OrderDTO;
            import com.greedy.section01.model.dto.OrderMenuDTO;
            
            public class OrderDAO {
            
            	private Properties prop = new Properties();
            	
            	public OrderDAO() {
            		try {
            			prop.loadFromXML(new FileInputStream("mapper/order-query.xml"));
            		} catch (IOException e) {
            			e.printStackTrace();
            		}
            	}
            
            	public List<CategoryDTO> selectAllCategory(Connection con) {
            		Statement stmt = null;
            		ResultSet rset = null;
            		
            		List<CategoryDTO> categoryList = new ArrayList<>();		// NPE(Null Point Exception 방지 코드
            		
            		String query = prop.getProperty("selectAllCategory");
            
            		try {
            			stmt = con.createStatement();
            			rset = stmt.executeQuery(query);
            			
            			while(rset.next()) {
            				CategoryDTO category = new CategoryDTO();
            				category.setCode(rset.getInt("CATEGORY_CODE"));
            				category.setName(rset.getString("CATEGORY_NAME"));
            				category.setRefCode(rset.getInt("REF_CATEGORY_CODE"));
            				
            				categoryList.add(category);
            			}
            		} catch (SQLException e) {
            			e.printStackTrace();
            		} finally {
            			close(rset);
            			close(stmt);
            		}
            		
            		return categoryList;
            	}
            
            	public List<MenuDTO> selectMenuBy(Connection con, int categoryCode) {
            		PreparedStatement pstmt = null;
            		ResultSet rset = null;
            		
            		List<MenuDTO> menuList = new ArrayList<>();
            		
            		String query = prop.getProperty("selectMenuByCategory");
            		
            		try {
            			pstmt = con.prepareStatement(query);
            			pstmt.setInt(1, categoryCode);
            			
            			rset = pstmt.executeQuery();
            			while(rset.next()) {
            				MenuDTO menu = new MenuDTO();
            				menu.setCode(rset.getInt("MENU_CODE"));
            				menu.setName(rset.getString("MENU_NAME"));
            				menu.setPrice(rset.getInt("MENU_PRICE"));
            				menu.setCategoryCode(rset.getInt("CATEGORY_CODE"));
            				menu.setOrderableStatus(rset.getString("ORDERABLE_STATUS"));
            				
            				menuList.add(menu);
            			}
            			
            		} catch (SQLException e) {
            			e.printStackTrace();
            		} finally {
            			close(rset);
            			close(pstmt);
            		}
            		
            		return menuList;
            	}
            
            	public int registOrder(Connection con, OrderDTO order) {
            		PreparedStatement pstmt = null;
            		int result = 0;
            		
            		String query = prop.getProperty("insertOrder");
            		
            		try {
            			pstmt = con.prepareStatement(query);
            			pstmt.setString(1, order.getDate());
            			pstmt.setString(2, order.getTime());
            			pstmt.setInt(3, order.getTotalOrderPrice());
            			
            			result = pstmt.executeUpdate();
            		} catch (SQLException e) {
            			e.printStackTrace();
            		} finally {
            			close(pstmt);
            		}
            		
            		return result;
            	}
            
            	public int selectLastOrderCode(Connection con) {
            		Statement stmt = null;
            		ResultSet rset = null;
            		
            		int lastOrderCode = 0;
            		
            		String query = prop.getProperty("selectLastOrderCode");
            		
            		try {
            			stmt = con.createStatement();
            			rset = stmt.executeQuery(query);
            			if(rset.next()) {
            				lastOrderCode = rset.getInt("CURRVAL");
            			}
            		} catch (SQLException e) {
            			e.printStackTrace();
            		} finally {
            			close(rset);
            			close(stmt);
            		}
            		
            		return lastOrderCode;
            	}
            
            	public int registOrderMenu(Connection con, OrderMenuDTO orderMenu) {
            		PreparedStatement pstmt = null;
            		int result = 0;
            		
            		String query = prop.getProperty("insertOrderMenu");
            		
            		try {
            			pstmt = con.prepareStatement(query);
            			pstmt.setInt(1, orderMenu.getOrderCode());
            			pstmt.setInt(2, orderMenu.getMenuCode());
            			pstmt.setInt(3, orderMenu.getAmount());
            			
            			result = pstmt.executeUpdate();
            		} catch (SQLException e) {
            			e.printStackTrace();
            		} finally {
            			close(pstmt);
            		}
            		
            		return result;
            	}
            }
            ```
            
    - DB Layer
        - 프로그램에서 사용할 정보들이 담긴 공간.
            
            ```java
            -- 시퀀스 생성
            DROP SEQUENCE SEQ_CATEGORY_CODE;
            DROP SEQUENCE SEQ_MENU_CODE;
            DROP SEQUENCE SEQ_ORDER_CODE;
            DROP SEQUENCE SEQ_PAYMENT_CODE;
            
            CREATE SEQUENCE SEQ_CATEGORY_CODE;
            CREATE SEQUENCE SEQ_MENU_CODE;
            CREATE SEQUENCE SEQ_ORDER_CODE;
            CREATE SEQUENCE SEQ_PAYMENT_CODE;
            
            -- 테이블 생성
            DROP TABLE tbl_category CASCADE CONSTRAINTS;
            DROP TABLE tbl_menu CASCADE CONSTRAINTS;
            DROP TABLE tbl_order CASCADE CONSTRAINTS;
            DROP TABLE tbl_order_menu CASCADE CONSTRAINTS;
            DROP TABLE tbl_payment CASCADE CONSTRAINTS;
            DROP TABLE tbl_payment_order;
            
            -- category 테이블 생성
            CREATE TABLE tbl_category
            (
                category_code    NUMBER NOT NULL,
                category_name    VARCHAR2(30) NOT NULL,
                ref_category_code    NUMBER
            );
            
            COMMENT ON COLUMN tbl_category.category_code IS '카테고리코드';
            COMMENT ON COLUMN tbl_category.category_name IS '카테고리명';
            COMMENT ON COLUMN tbl_category.ref_category_code IS '상위카테고리코드';
            COMMENT ON TABLE tbl_category IS '카테고리';
            
            CREATE UNIQUE INDEX index_category_code ON tbl_category
            ( category_code );
            
            ALTER TABLE tbl_category
             ADD CONSTRAINT pk_category_code PRIMARY KEY ( category_code )
             USING INDEX index_category_code;
            
            ALTER TABLE tbl_category
             ADD CONSTRAINT fk_ref_category_code FOREIGN KEY ( ref_category_code )
             REFERENCES tbl_category ( category_code);
            
            CREATE TABLE tbl_menu
            (
                menu_code    NUMBER NOT NULL,
                menu_name    VARCHAR2(30) NOT NULL,
                menu_price    NUMBER NOT NULL,
                category_code    NUMBER NOT NULL,
                orderable_status    CHAR(1) NOT NULL
            );
            
            COMMENT ON COLUMN tbl_menu.menu_code IS '메뉴코드';
            COMMENT ON COLUMN tbl_menu.menu_name IS '메뉴명';
            COMMENT ON COLUMN tbl_menu.menu_price IS '메뉴가격';
            COMMENT ON COLUMN tbl_menu.category_code IS '카테고리코드';
            COMMENT ON COLUMN tbl_menu.orderable_status IS '주문가능상태';
            COMMENT ON TABLE tbl_menu IS '메뉴';
            
            CREATE UNIQUE INDEX index_menu_code ON tbl_menu
            ( menu_code );
            
            ALTER TABLE tbl_menu
             ADD CONSTRAINT pk_menu_code PRIMARY KEY ( menu_code )
             USING INDEX index_menu_code;
            
            ALTER TABLE tbl_menu
             ADD CONSTRAINT fk_category_code FOREIGN KEY ( category_code )
             REFERENCES tbl_category ( category_code );
            
            CREATE TABLE tbl_order
            (
                order_code    NUMBER NOT NULL,
                order_date    VARCHAR2(8) NOT NULL,
                order_time    VARCHAR2(8) NOT NULL,
                total_order_price    NUMBER NOT NULL
            );
            
            COMMENT ON COLUMN tbl_order.order_code IS '주문코드';
            COMMENT ON COLUMN tbl_order.order_date IS '주문일자';
            COMMENT ON COLUMN tbl_order.order_time IS '주문시간';
            COMMENT ON COLUMN tbl_order.total_order_price IS '총주문금액';
            COMMENT ON TABLE tbl_order IS '주문';
            
            CREATE UNIQUE INDEX index_order_code ON tbl_order
            ( order_code );
            
            ALTER TABLE tbl_order
             ADD CONSTRAINT pk_order_code PRIMARY KEY ( order_code )
             USING INDEX index_order_code;
            
            CREATE TABLE tbl_order_menu
            (
                order_code NUMBER NOT NULL,
                menu_code    NUMBER NOT NULL,
                order_amount    NUMBER NOT NULL
            );
            
            COMMENT ON COLUMN tbl_order_menu.order_code IS '주문코드';
            COMMENT ON COLUMN tbl_order_menu.menu_code IS '메뉴코드';
            COMMENT ON COLUMN tbl_order_menu.order_amount IS '주문수량';
            COMMENT ON TABLE tbl_order_menu IS '주문별메뉴';
            CREATE UNIQUE INDEX index_comp_order_menu_code ON tbl_order_menu
            ( order_code,menu_code );
            
            ALTER TABLE tbl_order_menu
             ADD CONSTRAINT pk_comp_order_menu_code PRIMARY KEY ( order_code, menu_code )
             USING INDEX index_comp_order_menu_code;
            
            ALTER TABLE tbl_order_menu
             ADD CONSTRAINT fk_order_menu_order_code FOREIGN KEY ( order_code )
             REFERENCES tbl_order ( order_code );
            
            ALTER TABLE tbl_order_menu
             ADD CONSTRAINT fk_order_menu_menu_code FOREIGN KEY ( menu_code )
             REFERENCES tbl_menu ( menu_code );
             
            CREATE TABLE tbl_payment
            (
                payment_code    NUMBER NOT NULL,
                payment_date    VARCHAR2(8) NOT NULL,
                payment_time    VARCHAR2(8) NOT NULL,
                payment_price    NUMBER NOT NULL,
                payment_type    VARCHAR2(6) NOT NULL
            );
            
            COMMENT ON COLUMN tbl_payment.payment_code IS '결제코드';
            COMMENT ON COLUMN tbl_payment.payment_date IS '결제일';
            COMMENT ON COLUMN tbl_payment.payment_time IS '결제시간';
            COMMENT ON COLUMN tbl_payment.payment_price IS '결제금액';
            COMMENT ON COLUMN tbl_payment.payment_type IS '결제구분';
            COMMENT ON TABLE tbl_payment IS '결제';
            
            CREATE UNIQUE INDEX index_payment_code ON tbl_payment
            ( payment_code );
            
            ALTER TABLE tbl_payment
             ADD CONSTRAINT pk_payment_code PRIMARY KEY ( payment_code)
             USING INDEX index_payment_code;
            
            CREATE TABLE tbl_payment_order
            (
                order_code    NUMBER NOT NULL,
                payment_code    NUMBER NOT NULL
            );
            
            COMMENT ON COLUMN tbl_payment_order.order_code IS '주문코드';
            COMMENT ON COLUMN tbl_payment_order.payment_code IS '결제코드';
            COMMENT ON TABLE tbl_payment_order IS '결제별주문';
            
            CREATE UNIQUE INDEX index_comp_payment_order_code ON tbl_payment_order
            ( payment_code,order_code );
            
            ALTER TABLE tbl_payment_order
             ADD CONSTRAINT pk_comp_payment_order_code PRIMARY KEY ( payment_code,order_code )
             USING INDEX index_comp_payment_order_code;
            
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '식사', null);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '음료', null);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '디저트', null);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '한식', 1);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '중식', 1);
            
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '일식', 1);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '퓨전', 1);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '커피', 2);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '쥬스', 2);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '기타', 2);
            
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '동양', 3);
            INSERT INTO TBL_CATEGORY VALUES (SEQ_CATEGORY_CODE.NEXTVAL, '서양', 3);
            
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '열무김치라떼', 4500, 8, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '우럭스무디', 5000, 10, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '생갈치쉐이크', 6000, 10, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '갈릭미역파르페', 7000, 10, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '앙버터김치찜', 13000, 7, 'Y');
            
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '생마늘샐러드', 12000, 4, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '민트미역국', 15000, 4, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '한우딸기국밥', 20000, 4, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '홍어마카롱', 9000, 12, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '코다리마늘빵', 7000, 12, 'Y');
            
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '정어리빙수', 10000, 10, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '날치알스크류바', 2000, 10, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '직화구이젤라또', 8000, 12, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '과메기커틀릿', 13000, 6, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '죽방멸치튀김우동', 11000, 6, 'Y');
            
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '흑마늘아메리카노', 9000, 8, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '아이스가리비관자육수', 6000, 10, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '붕어빵초밥', 35000, 6, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '까나리코코넛쥬스', 9000, 9, 'Y');
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '마라깐쇼한라봉', 22000, 5, 'Y');
            
            INSERT INTO TBL_MENU VALUES (SEQ_MENU_CODE.NEXTVAL, '돌미나리백설기', 5000, 12, 'Y');
            
            COMMIT;
            ```
            
- 모델링 과정에 따른 DTO 참조
    - 모델링 과정에 따라 DTO의 참조구조가 달라진다.
        
        ![Untitled](JDBC_교육/Untitled%208.png)
        
        - 위 코드의 경우, OrderMenuDTO Class의 orderCode는 OrderDTO의 code를 참조하는 FK인 동시에 OrderMenuDTO의 FK다. 이를 PFK, 복합키라고도 한다.

---

[단위 테스트](JDBC_교육/단위_테스트.md)