# JAVA Generic

# 제네릭(Generic)

- 제네릭의 사용
    - 제네릭은 클래스 내부에서 사용할 데이터 타입을 외부에서 지정하는 기법을 의미한다.
    - 필드 영역에서 선언한다. 선언 이후 <>영역 안에 데이터타입을 지정했을 때, 해당 데이터 타입이 된다. 이를 코드로 표현하면 아래와 같다.
    
    ```java
    package study;
    
    class Person<T>{
    	public T info;
    }
    
    public class Generic {
    
    	public static void main(String[] args) {
    		Person<String> p1 = new Person<String>();
    		Person<StringBuilder> p2 = new Person<StringBuilder>();
    	}
    
    }
    ```
    
    - 위 코드의 경우, 필드 영역에 Person이라는 ClassName으로 지정된 <T> Generic이 블럭 안에서 public T info;를 통해 선언되는 형식이다.
    - 즉, Person은 public 접근제한자를 가진, 아직 데이터타입이 지정되지 않은 Generic이 된다.
    - 이후, main method에서 new Person<String>(); 을 통해 Person의 데이터 타입이 String으로 지정된다. 이후, 레퍼런스 변수 p1도 String 데이터 타입으로 지정되어야 하기에 Person<String> p1 = new Person<String>(); 이 되는 방식이다.
- 제네릭의 사용이유
    
    ```java
    package study;
    class StudentInfo{
    	public int grade;
    	StudentInfo(int grade){ this.grade = grade; }
    }
    class StudentPerson{
    	public StudentInfo info;
    	StudentPerson(StudentInfo info){ this.info = info;}
    }
    class EmployeeInfo{
    	public int rank;
    	EmployeeInfo(int rank){ this.rank = rank; }
    }
    class EmployeePerson{
    	public EmployeeInfo info;
    	EmployeePerson(EmployeeInfo info){ this.info = info; }
    }
    
    public class Generic2 {
    	public static void main(String[] args) {
    		StudentInfo si = new StudentInfo(2);
    		StudentPerson sp = new StudentPerson(si);
    		System.out.println(sp.info.grade);
    		EmployeeInfo ei = new EmployeeInfo(1);
    		EmployeePerson ep = new EmployeePerson(ei);
    		System.out.println(ep.info.rank);
    	}
    
    }
    ```
    
    ```java
    package study;
    class StudentInfo{
    	public int grade;
    	StudentInfo(int grade){ this.grade = grade; }
    }
    class EmployeeInfo{
    	public int rank;
    	EmployeeInfo(int rank){ this.rank = rank; }
    }
    class Person1{
    	public Object info;
    	Person1(Object info){ this.info = info; }
    }
    
    public class Generic2 {
    	public static void main(String[] args) {
    		Person1 p1 = new Person1("부장");
    		EmployeeInfo ei = (EmployeeInfo)p1.info;
    		System.out.println(ei.rank);
    	}
    
    }
    ```
    
    - 위 코드의 중복되는, 동일한 역할을 하는 class인  StudentPerson과 EmployeePerson는 상속받은 class가 아니기에 다형화가 불가능하다.
    - 그 같은 문제를 해결하기 위해 모든 자료형의 선조인 Object를 사용했을 경우, Object는 모든 데이터 타입이 가능한 데이터 타입이기에, info 데이터타입에 맞지 않는 “부장”을 Argument로 지정해도 컴파일 에러가 발생하지 않는다. (실제로는 에러임에도)
    - 이 같은 경우를 자바에서는 타입이 안전하지 않다고 한다.
    - 근복적으로 자바에서 자료형을 변수의 앞에 지정하는 이유는, 해당 변수가 해당 자료형에 해당하는 데이터타입만 사용할 수 있게 함에 있는데, Object를 사용했을 경우 그 목적을 해치게 되는 것.
    - 이러한 경우에 대처하기 위해, 안전성과 편의성을 모두 챙기기 위한 것이 바로 제네릭(Generic)이다.
- 제네릭의 특징 1
    - 복수의 제네릭 사용 시에는 콤마(,)로 구분하며, 서로 다른 이름을 사용해야 한다.
    - 제네릭은 참조 자료형만 가능하며, 기본 자료형은 불가능하다.
    - 기본 자료형으로 제네릭을 사용하고 싶다면, 레퍼 클래스를 사용하면 된다.
    - 레퍼 클래스(Wrapper class)는 기본 자료형을 객체인 것처럼 만들 수 있는 객체, 클래스.
    - intValue : 레퍼 클래스 Integer가 담고 있는 원래 숫자를 기본 데이터타입 int로 돌려주는 메소드.
    
    ```java
    class EmployeeInfo{
    	public int rank;
    	EmployeeInfo(int rank) {this.rank = rank;}
    }
    class Person<T, S> {
    	public T info;
    	public S id;
    	Person(T info, S id) { 
    		this.info = info;
    		this.id = id;
    	}
    }
    public class Generic {
    	public static void main(String[] args) {
    		Integer id = new Integer(1);
    		Person<EmployeeInfo, Integer> p1 = new Person<EmployeeInfo, Integer> 
    		(new EmployeeInfo(1), id);
    		System.out.println(p1.id.intValue());
    	}
    }
    ```
    
    - 위 코드의 경우, EmployeeInfo 는 T info, Integer는 S id의 자료형이 된다.
- 제네릭의 특징 2
    
    ```java
    package study;
    
    class EmployeeInfo{
    	public int rank;
    	EmployeeInfo(int rank) {this.rank = rank;}
    }
    class Person<T, S> {
    	public T info;
    	public S id;
    	Person(T info, S id) { 
    		this.info = info;
    		this.id = id;
    	}
    	public <U> void printInfo(U info) {
    		System.out.println(info);
    	}
    }
    public class Generic3 {
    	public static void main(String[] args) {
    		EmployeeInfo e = new EmployeeInfo(1);
    		Integer i = new Integer(10);
    		Person p1 = new Person(e, i);
    		p1.printInfo(e);
    	}
    }
    ```
    
    - Generic의 경우,매개변수와 전달인자를 통해 생략이 가능하다.
    - 또한 method 영역에서도 제네릭을 사용할 수 있는데,  접근 제어자와 리턴 값 사이에 넣어 사용한다.
- 제네릭의 제한
    - extends 와 implements를 활용한 class와 interface의 상속 개념 소개.
    
    ```java
    package study;
    interface Info{
    	int getLevel();
    	
    }
    class EmployeeInfo3 implements Info{
    	public int rank;
    	EmployeeInfo3(int rank) {this.rank = rank;}
    	public int getLevel() {
    		return this.rank;
    	}
    }
    class Person4<T extends Info> {
    	public T info;
    	Person4(T info) {
    		this.info = info; 
    		info.getLevel();
    		}
    }
    public class Generic3 {
    	public static void main(String[] args) {
    		Person4<EmployeeInfo3> p1 = new Person4<EmployeeInfo3>(new EmployeeInfo3(1));
    	}
    }
    ```
    
    - 위의 코드에서, generic T는 Info로부터 상속을 받고 있다.
    - 따라서, 아래의 info.getLevel(); 에 컴파일 에러가 발생하지 않는다.
    - 이는, generic 역시 class와 마찬가지로 상속을 받을 수 있음을 의미한다.
    - 다만, 뒤의 extends Info를 제거할 경우, T는 T extends Object의 의미를 가진다.
    - 따라서 Person4에 없는, EmployeeInfo3에 있는 getLevel method를 사용할 수 없어 컴파일 에러가 발생한다.