# 뉴렉처 JDBC

# Orientation

- JDBC란 무엇인가?
    - SQL을 작성할 수 있는 사람들을 위한 쿼리 실행 도구는 DB Client 프로그램.
    - SQL을 작성할 수 없는 사람들을 위한 쿼리 실행 도구는 업무용 프로그램.
    - 업무용 프로그램을 위한 쿼리 실행도구는 API
    - DBMS(Oracle, My SQL)에 따라 SQL문의 문법은 조금씩 다르다. 그렇기에 API, 업무용 프로그램의 문법 역시 다를 수밖에 없다. 여기서 프로그래머는 DBMS의 종류에 따라 동일한 기능의 API라도 다른 코드의 프로그램을 만들고, 다른 언어를 알아야 한다는 문제가 있다.
    - 이와 같은 문제점에서 탄생한 것이 JDBC.
    - JDBC는 JAVA가 제공하는 프로그램으로, 서로 다른 SQL문의 문법을 통일하여 프로그램에서 JDBC를 거쳐 동일한 코드로 다른 DBMS에 접속할 수 있게 도와준다.
    - 즉, 어댑터와 같은 역할을 하는 것이 JDBC.
    - 단, 서로 다른 DBMS에 따라 다른 플러그인 자체는 필요한데, 그 플러그인이 바로 드라이버.
        
        ![Untitled](뉴렉처_JDBC/Untitled.png)
        
    - 좌측부터
    업무용 프로그램 → JDBC → Driver → DBMS
    
- DBMS와 JDBC Driver 준비하기
    - OJDBS 설치와 Class path 연결 방법 소개
- JDBC 기본코드의 이해
    - JDBC 자체에는 아무런 기능이 없다. 실질적인 기능은 각 DBMS의 Driver들이 가지고 있기에, 해당 드라이버를 로드하는 과정 (객체화)이 필요하다.
        - 그 방법(코드)는 Class.forName(”oracle.jdbc.driver.OracleDriver”);
    - 객체화를 통해 메모리에 객체가 잡힌 뒤, DriverManager를 통해 Connection 객체를 생성한다.
        - 그 방법은 Connection con = DriverManager.getConnection(...);
            
            ![Untitled](뉴렉처_JDBC/Untitled%201.png)
            
    - 그 뒤 실행 도구를 생성하고
        - Statement st = con.createStatement();
            
            ![Untitled](뉴렉처_JDBC/Untitled%202.png)
            
    - 쿼리를 실행 한 결과를 얻어온다.
        - ResultSet rs = st.executeQuery(sql);
    - 4가지 모두 객체를 생성하지만, new 연산자가 사용되지 않는다. (그럼에도 객체를 생성하는 것. 혼동하지 말자.)
    - DriverManager가 없으면 Connection을 만들 수 없고, 연결을 하지 못 한 상태에서 실행 도구를 만들 수 없고, 실행 하지 않고 결과를 얻을 수 없다는 흐름을 이해하자.
    - ResultSet에서 rs.next(); - rs.getString(); 가 필요한 이유: ResultSet의 결과인 DBMS의 결과 집합은 서버에 그대로 있고, 결과 집합의 데이터를 레코드 단위로 하나씩 돌려주는 과정을 가진다. 이 때 하나 하나의 단위가 커서단위. 즉, iterator처럼, 커서 단위로 데이터를 하나씩 읽고 반복문을 통해 반환하는 것.
        - BOF 에서 EOF까지 한 칸(인덱스, 레코드) 단위로 ResultSet에 담긴 뒤 반환되고, 담기고 반환되는 과정을 반복하는 것.
            
            ![Untitled](뉴렉처_JDBC/Untitled%203.png)
            
    - ResultSet의 객체가 만들어진 것은 결과 집합을 받은 것이 아니다. 결과 집합을 이용할 수 있는 상태가 된 것.
    
- 쿼리 실행하기 실습
    - DBMS에 수업에 활용할 데이터 입력 과정.
    - ECLIPSE에서 Driver, Connection, Statement, ResultSet 설정
    - 이후 IF문을 활용, next()와 getString으로 출력하는 과정.
    - DMBS에서 출력을 위한 행 하나 INSERT
- 혼자 풀어보는 문제#1
    - 수업 전 혼자 문제를 풀어보는 시간.
    - 전체 컬럼을 모두 출력해보자.
    - 컬럼의 형식에 따라 다른 형식으로 출력해보자.
    - 2번째 레코드, 3번째 레코드를 반복문으로 작성 해보자.
        
        ```sql
        package ex1;
        
        import java.sql.Connection;
        import java.sql.DriverManager;
        import java.sql.ResultSet;
        import java.sql.SQLException;
        import java.sql.Statement;
        import java.util.Date;
        
        public class Program {
        
        	public static void main(String[] args) throws ClassNotFoundException, SQLException {
        
        		String url = "jdbc:oracle:thin:@localhost:1521/xe";
        		String sql = "SELECT * FROM NOTICE";
        		
        		Class.forName("oracle.jdbc.driver.OracleDriver");
        		Connection con = DriverManager.getConnection(url, "C##STUDY", "STUDY");
        		Statement st = con.createStatement();
        		ResultSet rs = st.executeQuery(sql);
        
        		
        		while(rs.next()) {
        			int id = rs.getInt("id");
        			String title = rs.getString("TITLE");
        			String writerld = rs.getString("WRITER_ID");
        			Date regDate = rs.getDate("REGDATE");
        			String content = rs.getString("CONTENT");
        			int hit = rs.getInt("hit");
        			System.out.println(rs.getInt("id") + ", " +rs.getString("TITLE") + ", " 
        							  +rs.getString("WRITER_ID") + ", " +rs.getDate("REGDATE") + ", "
        							  +rs.getString("CONTENT") + ", " +rs.getInt("hit"));
        		}
        		
        		rs.close();
        		st.close();
        		con.close();
        	}
        
        }
        ```
        
- 문제#1 풀이겸 문제#2
    - 이전 시간의 문제 정답 확인
    - 새로운 문제: 조회수(hit)이 10 이상인 게시글만 출력.
    - 기존 내용에 sql  초기화 구문 부분만 WHERE절 붙여 수정
        
        ```sql
        String sql = "SELECT * FROM NOTICE WHERE HIT >= 10";
        ```
        
- 문제#2 풀이와 SQL을 잘해야 하는 이유
    - 필터링을 자바가 아닌 SQL문에서 하는 이유: 필요한 데이터는 한정적인데, 모든 데이터를 가지고 오는 것은 리소스 낭비이기 때문.
        
        ![Untitled](뉴렉처_JDBC/Untitled%204.png)
        
    - 데이터 필터링, 정렬, 그룹화 등의 모든 데이터 연산은 데이터 베이스에서 처리하는 편이 더 효율적이다.
    - 자바는 UI 레이아웃 만, 데이터 가공처리는 SQL이 담당한다.
- 트랜잭션 처리란
    
    ![Untitled](뉴렉처_JDBC/Untitled%205.png)
    
    - (ACID의 Automicity 오타: Atomicity, 원자성)
    → 다수의 SQL문이 있더라도 마치 하나인 것처럼 깨지지 않도록 처리해야 한다.
    - Consistency, 일관성
    → 데이터의 결함이 발생하지 않도록 일관성을 유지해야 한다.  트랜잭션 과정에서 결함이 생기는 데이터를 만들면 안된다.
    - Isolation, 고립성
    → 데이터의 사용은 개인이 아닌 다수에 의해 이루어지므로, 하나의 트랜잭션이 끝나기 전까지는 접근을 불가하게 하는 것.
    - Durability, 지속성
    → CRUD 이후 해당 작업을 적용하게 하는 것. 영구적으로 데이터를 저장하는 것. (Commit)
    - 위의 일괄 공개에서 SQL문은 2개지만, 실질적인 업무는 일괄 공개라는 하나. 이처럼, 개념적인 일의 단위가 트랜잭션
- 데이터 입력을 위한 쿼리문 준비하기
    - DBMS에서 제약조건 추가하는 방법과 SEQUENCE 활용하는 방법 안내.
    - 단, 연관된 다른 강좌를 듣지 않은 상태라 적합한 DB가 없고, 따로 제공되는 테이블이 없어 확인하기 어려운 부분이 있음.
- 데이터 입력하기와 Prepared Statement
    - JAVA를 통해 DB 내용을 수정(UPDATE)하는 방법 학습.
    - 주의할 점:  Prepared Statement를 사용하며, result 값으로 Query가 아닌, executeUpdate를 이용해야 한다.
        
        ```sql
        package ex1;
        
        import java.sql.Connection;
        import java.sql.DriverManager;
        import java.sql.PreparedStatement;
        import java.sql.ResultSet;
        import java.sql.SQLException;
        import java.sql.Statement;
        import java.util.Date;
        
        public class Program2 {
        
        	public static void main(String[] args) throws ClassNotFoundException, SQLException {
        
        		String title = "TEST2";
        		String writer_Id = "newlec";
        		String content = "hahaha";
        		String files = "";
        		
        		String url = "jdbc:oracle:thin:@localhost:1521/xe";
        		String sql = "INSERT INTO notice (		"
        				+ "   title, "
        				+ "   wirter_id, "
        				+ "	  content, "
        				+ "   files"
        				+ ") VALUES (?,?,?,?)";
        		
        		Class.forName("oracle.jdbc.driver.OracleDriver");
        		Connection con = DriverManager.getConnection(url, "C##STUDY", "STUDY");
        //		Statement st = con.createStatement();
        			
        		PreparedStatement st =con.prepareStatement(sql);
        		st.setString(1, title);
        		st.setString(2, writer_Id);
        		st.setString(3, content);
        		st.setString(4, files);
        		
        		int result = st.executeUpdate();
        		
        		System.out.println(result);
        		
        //		rs.close();
        		st.close();
        		con.close();
        	}
        
        }
        ```
        
    - sql 삽입 시 values 값으로 ?를 이용할 수 있다.

---

- 이후 강의는 이전 Oracle 수업에 대한 의존성이 너무 강해 생략함.