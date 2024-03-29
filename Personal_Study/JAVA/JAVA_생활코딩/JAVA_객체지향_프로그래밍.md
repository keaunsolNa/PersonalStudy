# JAVA 객체지향 프로그래밍

- 오리엔테이션
    - 1.  수업소개
        - 기존의 메소드를 묶어주는 수납상자, class를 중심으로 이루어지는 방법론이 객체 지향 언어. 단, 이 정의는 편협한 정의.
        - 클래스, 인스턴스, 상속, 인터페이스의 개념들을 배워보자.
    - 2.  남의 클래스, 남의 인스턴스
        - method 와 class를 불러들이는 방법 연습.
        - Math. 메소드와 FileWriter 메소드 활용.
        - 1회용으로 작업을 끝마치면 되는 것들은 class에 있는 method를 그대로 사용한다. 다만, 긴 맥락을 가지고 작업해야 하는 경우, class를 복제한 뒤 복제본을 제어하는 방식으로 사용하는 편이 더 효율적이다.
        
        ```java
        public static void main(String[] args) throws IOException {
        		
        		System.out.println(Math.PI);
        		System.out.println(Math.floor(Math.PI));
        		System.out.println(Math.ceil(Math.PI));
        		
        		FileWriter f1 = new FileWriter("data.txt");
        		f1.write("Hello");
        		f1.write(" Java");
        		f1.close();
        		
        		FileWriter f2 = new FileWriter("data2.txt");
        		f2.write("Hello");
        		f2.write(" Java2");
        		f2.close();
        		
        		f1.write("!!!");
        		f1.close();
        		
        	}
        ```
        
        - 위의 코드에서, System, Math, FileWriter는 class.
        - f1, f2에 담기는 값들이 인스턴스.
    - 3.  변수와 메소드
        - 변수는 변수가 선언된 method안에서만 사용 가능하다. (지역변수)
        - 이 때 변수를 전역변수로 바꾸면 다른 method에서도 사용 가능하다.
        - argument와 parameter를 이용, 메소드를 주고 받는 방법.
        - 클래스를 이용하는 이유, 사용하지 않을 때의 불편 사항 확인.
- 객체 지향
    - 4.  클래스 - 존재 이유와 기본형식
        - 클래스의 존재 이유는 결국 서로 관련성 있는 method를 하나의 클래스로 묶어 분류함으로서, 유지보수성을 높이고 가독성을 높여주는 효과에 있다.
        - 클래스에 소속되어 있는 것들을 통틀어 멤버라고 한다.
    - 4-2.  클래스 - 형식
        - 하나의 파일 안에서 여러 개의 class를 만들면, 각각의 class가 별개의 파일로서 존재하게 된다.
        - 또한, 하나의 클래스 안의 class를 다른 동일한 이름의 class파일을 만들어 옮길 수 있다.
        - 이는 코드가 길어질 수록 가독성이 낮아지기에, 파일을 여러개로 쪼개 코드의 유지보수성을 높여주기 위함이다.
    - 5. 인스턴스
        - 인스턴스는 기존에 존재하는 class들을 복제하는 것.
        - 타 클래스에 정의된 method를 new 연산자를 통해 인식, 이 후 인스턴스화 한 변수명 만으로 method를 불러오는 효과가 있다.
        
        ```java
        Print p1 = new Print();
        		p1.delimiter = "----";
        		p1.A();
        		p1.A();
        		p1.B();
        		p1.B();
        
        /////////////////////////////
        
        class Print{
        public String delimiter = "";
        
        public void A() {
        		System.out.println(delimiter);
        		System.out.println("A");
        		System.out.println("A");
        	}
        }
        ```
        
        - 위 코드의 경우, Print라는 이름의 class를 new 연산자를 통해 인식할 수 있게 한 뒤
        - p1.delimiter를 통해 Print class에 있는 delimiter라는 String public 변수를 가져온 것.
        - 이후 p1.A() 만을 이용해 Print class에 있는 A method 전체를 읽어온다.
    - 6.  static
        - class 를 통해 직접 instance변수와 method에 접근은 금지되어 있다.
        - static class의 변수를 변경하면 모든 static instance의 변수의 값이 변경된다. 마찬가지로 static instance의 변수 역시 변경되면 모든 static class의 변수의 값도 변경된다. 이는 instance의 static이 class의 static을 가리키는 형태로 링크되어 있기 때문이다. 이는 변수 뿐만 아니라, method에도 동일하게 해당된다.
        - 반대로 non static, instance의 경우 링크가 아닌 class의 변수나 method를 복제하여 instance를 생성한 느낌이기에, 둘 중 하나가 변경되더라도 나머지가 변경되지는 않는다.
        
        ![8.png](JAVA_객체지향_프로그래밍/8.png)
        
        - class의 static과 non static의 method, instance의 관계 도식표.
    - 7.  생성자와 this
        - 생성자(constructor) : Java에서의 class는 생성자 method를 구현할 수 있는 기능을 제공한다. 생성자의 주요한 작업은 초기화. class와 동일한 이름의 method를 정의하면 그 method가 생성자. 인스턴스를 생성할 때, class와 동일한 이름의 method가 있다면, 인스턴스를 새로 생성하는 것이 아닌, 동일한 이름의 method를 호출하도록 되어 있다.
        - 단, 생성자의 매개변수는 기본적으로 인스턴스 변수를 가리키기 때문에,  생성자의 매개변수와 인스턴스 변수가 동일한 이름일 때, 혼동의 여지가 있다.
        - 이 때, this. 라는 키워드를 사용해 생성한 인스턴스를 가리키도록 할 수 있다.
        
        ```java
        public class MyOOP {
        	public static void main(String[] args) {
        		Print p1 = new Print("----");
        		p1.A();
        		p1.A();
        		p1.B();
        		p1.B();
        	}
        }
        ```
        
        ```java
        class Print{
        	public String delimiter = "";
        	public Print(String delimiter) {
        		this.delimiter = delimiter;
        	}
        	 
        	public void A() {
        		System.out.println(this.delimiter);
        		System.out.println("A");
        		System.out.println("A");
        	}
        }
        ```
        
        - 위 코드를 통해 설명하자면, MyOOP class에서 new 연산자를 통해 생성된 Print p1 = new Print(”——”); 의 경우.
        - 새로운 인스턴스를 만드는 것이 아닌, 기존에 존재하는 Print class를 가리키게 된다.
        - 이후 Print class의 public String delimiter = “”; 를 통해 String 자료형을 가진 delimiter라는 이름의 인스턴스 변수를 만든다.
        - 이후, public Print(String delimiter) {
        this.delimiter = delimiter;}
        를 통해 Print라는 class의 매개변수로 String 자료형을 가진 delimiter를 반환하게 된다.
        - 이후, 매개변수 delimiter는 this.delimiter = delimiter; 를 통해 위의 String delimiter = “”; 로 정의되는데.
        - 이 때 this. 를 사용하지 않는다면, 동일한 변수명으로 인한 혼란이 있을 수 있기에 this. 를 통해 delimiter가 생성한 인스턴스를 가리키고 있음을 명시한다.
- 객체 지향의 활용
    - 8-1. 활용 - 클래스화
        - 변수나 method를 여러개 사용하지 않고, 하나의 class로 묶어서 사용하는 방식의 장점 소개
        - 서로 연관된 변수의 경우, 하나의 class로 묶은 뒤, 해당 class의 method를 호출하는 방식으로 코드를 단정하게 하고, 유지보수의 수월함을 상승시켜 준다.
        
        ```java
        class Accounting{
        	 public static double valueOfSupply;
        	    public static double vatRate = 0.1;
        	    public static double getVAT() {
        	        return valueOfSupply * vatRate;
        	    }
        	    public static double getTotal() {
        	        return valueOfSupply + getVAT();
        	    }
        }
        public class AccountingApp {
            public static void main(String[] args) {
            	Accounting.valueOfSupply = 10000.0;
                System.out.println("Value of supply : " + Accounting.valueOfSupply);
                System.out.println("VAT : " + Accounting.getVAT());
                System.out.println("Total : " + Accounting.getTotal());
            }
        }
        ```
        
        - 위 코드의 경우, Accounting이라는 class 이름으로 method들을 묶은 뒤
        AccountingApp class에서 해당 class의 method를 호출하는 방식으로 수월하게 사용할 수 있다.
        - 이 같은 방법을 사용할 경우, 유사한 목적을 가진 method들을 하나의 class로 묶어 사용함으로 변수 혹은 method의 가시성을 높이고, 유지보수가 수월해진다.
    - 8-2. 활용 - 인스턴스화
        - class를 인스턴스화 하는 방법을 소개하며 인스턴스 하는 방식의 장점 소개.
        - class의 값이 지속적으로 변동되야 할 필요가 있을 때, 인스턴스화의 필요성이 커진다.
        - 뿐만 아니라, 하나의 class를 여러 상태가 돌려서 사용할 때. 버그의 가능성이 높아지게 된다.
        - 이 때 변동하는 각각의 class 값들을  인스턴스화 함으로서, 
        Accounting a1 = new Accounting();
        a1.valueOfSupply = 10000.0;
        메모리의 낭비를 줄이고, 버그의 가능성을 낮출 수 있다. 또한, 가시성과 유지보수성의 편의 역시 부여해준다.
        
        ```java
        class Accounting{
        	 public double valueOfSupply;
        	    public static double vatRate = 0.1;
        	    public Accounting(double valueOfSupply) {
        	    	this.valueOfSupply = valueOfSupply;
        	    }
        	    public double getVAT() {
        	        return valueOfSupply * vatRate;
        	    }
        	    public double getTotal() {
        	        return valueOfSupply + getVAT();
        	    }
        }
        public class AccountingApp {
            public static void main(String[] args) {
            	Accounting a1 = new Accounting(10000);
            	
            	Accounting a2 = new Accounting(20000);
            	
            	System.out.println("Value of supply : " + a1.valueOfSupply);
            	System.out.println("Value of supply : " + a2.valueOfSupply);
        
            	System.out.println("VAT : " + a1.getVAT());
            	System.out.println("VAT : " + a2.getVAT());
            	
            	System.out.println("Total : " + a1.getTotal());
            	System.out.println("Total : " + a2.getTotal());
            }
        }
        ```
        
        - 또한, 아래의 코드처럼 this. 명령자를 사용함으로서, 각각의 인스턴스값을 일일히 규정하지 않고, 인스턴스의 매개변수로 입력함으로서, 가시성과 유지보수성을 더 올릴 수 있다.
- 수업을 마치며
    - 단순한 코드로는 객체지향의 필요성을 느끼기 쉽지 않다. 다만 코드가 복잡해질 수록 객체지향의 필요성은 커진다.
    - 상속과 인터페이스 , 패키지 개념 소개와 필요성이 대두되는 순간 소개.