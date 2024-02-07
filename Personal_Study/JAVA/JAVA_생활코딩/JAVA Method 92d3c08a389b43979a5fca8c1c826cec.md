# JAVA Method

- 오리엔테이션
    - 1.  수업 소개
        - method = 복잡한 것을 정리해서 다시 단순하게 만드는 정리정돈의 도구.
        - 연관된 것들을 모아 그룹핑 하는 것이 method의 본질
        - method를 이용하면 복잡한 코드를 정리할 수 있으며, 이미 만들어둔 method를 재사용할 수 있다.
    - 2.  이미 익숙한 메소드
        - 원리를 이해하지 못 했어도, 이미 타인이 만든 method를 사용하고 있었다.
        - 또한, main method 역시 정의하고 사용하고 있던 것.
        - 이제부터 나만의 method를 만들고, main method를 이해하는게 수업의 목표.
- 메소드
    - 3.  메소드의 기본 형식
        - 메소드는 코드의 의미를 수월하게 파악할 수 있게 해주며, 유지보수성의 증가와 코드의 크기 감소, 중복의 제거의 효과 등이 있다.
        - 메소드의 지정은 Eclipse 기준, refactor에서 Extract Method 메뉴를 통해 손쉽게 지정할 수 있다.
        
        ```java
        public class WhyMethod {
        
        	public static void main(String[] args) {
        		
        		// 100000000
        		printTwoTimesA();
        		// 100000000
        		printTwoTimesA();
        		// 100000000
        		printTwoTimesA();
        		
        	}
        	
        	public static void printTwoTimesA() {
        		System.out.println("-");
        		System.out.println("A");
        		System.out.println("A");
        	}
        }
        ```
        
    - 4.  메소드의 입력
        
        ```java
        public class WhyMethod {
        	public static void main(String[] args) {
        			
        		printTwoTimes("a", "-");
        		printTwoTimes("a", "*");
        		printTwoTimes("a", "&");
        		printTwoTimes("b", "!");
        	}
        	
        	public static void printTwoTimes(String text, String delimiter) {
        		System.out.println(delimiter);
        		System.out.println(text);
        		System.out.println(text);
        	}
        }
        ```
        
        - method()의 ()안의 입력값에 따라 다른 출력값을 가지도록 해 보자.
        - printTwoTimes라는 이름의 method ()안에 들어오는 첫 번째 값은 String이며, {} 안에서 text라는 이름의 변수의 값이 된다.
        - 두번째 값은 String이며, {}안에서 delimiter(구분자)라는 이름의 변수 값이 된다.
        - public static void main(String[] args) {}
        에서 main은 method의 이름. main method는 약속.
        String[] 의 의미는 서로 연관된 문자열을 그룹핑하는 문자열 배열.
        args는 main이라는 method를 java가 실행할 때,  사용자가 실행할 때 주입해준 변수를 main method 안에서 사용해줄 수 있게 하는 것.
        - text, delimiter와 같은 변수를 메소드를 사용하는 쪽에서 주입한 값을 메소드 안으로 흘려 보내주는 매개자라 해서 매개변수, parameter라고 한다.
        - “a”, “-”, “b”와 같은 변수는 인자, argument.
    - 5.  메소드의 출력
        - 메소드의 return값 뒤에 있는 값이 메소드의 실행 결과가 된다.
        - 리턴 값은 메소드를 종료시키는 역할도 한다.
        - 메소드의 리턴 값의 자료형도 정해줘야 한다.
        - void는 리턴 값이 없다는 의미를 가지고 있다.
        
        ```java
        import java.io.FileWriter;
        import java.io.IOException;
        
        public class WhyMethod {
        	public static void main(String[] args) throws IOException {				
        		System.out.println(twoTimes("a","-"));
        		FileWriter fw = new FileWriter("out.txt");
        		fw.write(twoTimes("a","*"));
        		fw.close();						
        	}
        	public static String twoTimes(String text, String delimiter) {
        		String out = "";
        		out = out + delimiter + "\n";
        		out = out + text + "\n";
        		out = out + text + "\n";
        		return out;
        	}
        }
        ```
        
        - 메소드는 입력 값이 있고, 그것을 처리해서 출력해준다. 출력할 때 사용하는 핵심적인 키워드는 return. 어떤 데이터 타입을 return할지를 직접 적어줘야 한다.
    - 6.  메소드의 활용
        - 메소드를 활용하여 실제로 적용 해 보는 것.
        
        ```java
        public class AccountingApp {
        	public static double valueOfSupply = 10000.0;
        	public static double vatRate = 0.1;
        	public static double getVAT() {
        		return valueOfSupply * vatRate;
        	}
        	
        	public static double getTotal() {
        		return valueOfSupply + getVAT();
        	}
        	public static void main(String[] args) {
        				
        		System.out.println("value of Supply : " + valueOfSupply);
        		System.out.println("VAT : " + getVAT());
        		System.out.println("Total : " + getTotal());
        
        	}
        
        }
        ```
        
        - 변수를 그룹핑 하는 것이 메소드의 핵심.
        - 단 지역변수와 전역변수에 유의할 것.
        - 메소드의 핵심은 코드의 중복을 제거하고 재사용성을 높이는 것.
        - 아직 어렵지만 반복숙달로 익숙해지자.
- 수업을 마치며
    - 다음 목표는 객체 지향 프로그램 (Object Oriented Programming)
    - CLASSS 와 INSTANCE를 포괄적으로 객체라고 생각한 뒤, 객체를 뼈대로 프로그램을 만드는 것이 객체 지향 프로그램.
    - 객체 지향은 어렵다.
- 추가사항
    - 8.  Access Level Modifiers
        - public static void main(String[] args) {} 에서, public은 Access Level Modifiers로 
        public, protected, default, private의 4가지 종류가 있다.
        - Private : 같은 Class 안에서만 사용할 수 있는 내부적인 메소드.
        - public : 다른 Class 안에서도 사용할 수 있는 메소드.
        - 단, protected와 default는 나중에 객체 지향을 배울 때 학습하도록 한다.
    - 9. Static
        - static - class method
        - non static - instance method
        - 메소드가 인스턴스의 소속일 때는 static을 빼줘야 한다.
        - 메소드가 class의 소속일 때는 static이 있어야 한다. .
        
        ```java
        class Print{
        	public String delimiter;
        	public void a() {
        		System.out.println(this.delimiter);
        		System.out.println("a");
        		System.out.println("a");
        }
        	public void b() {
        		System.out.println(this.delimiter);
        		System.out.println("b");
        		System.out.println("b");
        	}
        	public static void c(String delimiter) {
        		System.out.println(delimiter);
        		System.out.println("b");
        		System.out.println("b");
        	}
        }
        public class staticMethod {
        	
        	public static void main(String[] args) {
        //		Print.a("-");
        //		Print.b("-");
        
        		Print t1 = new Print();
        		t1.delimiter = "-";
        		t1.a();
        		t1.b();
        		Print.c("$");
        		
        //		Print.b("*");
        //		Print.b("*");
        		
        		Print t2 = new Print();
        		t2.delimiter = "*";
        		t2.a();
        		t2.b();
        
        	}
        
        }
        ```