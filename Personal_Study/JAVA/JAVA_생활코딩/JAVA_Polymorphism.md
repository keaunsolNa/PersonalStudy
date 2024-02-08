# JAVA Polymorphism

# 다형성(Polymorphsum)

<aside>
💡 사용자가 입력한 값에 따라서 다른 인스턴스를 넣어줄 수 있다는 특징이 다형성의 가장 큰 장점이다.

</aside>

- 메소드와 다형성
    - 다형성이란 하나의 메소드나 클래스가 있을 때 이것들이 다양한 방법으로 동작하는 것을 의미한다.
    - 이는 메소드의 이름이 같더라도, 시그니쳐가 다르면 다른 메소드로 인식하기 때문이다.
    - 따라서, 아래의 코드가 있을 때. 동일한 인스턴스 변수인 o를 이용해도 int값과 String값으로 다르게 출력이 가능하다.
    
    ```java
    package Project.polymorphism;
    
    public class polymorphism0 {
    	
    	public void a (int param){
    		System.out.println("숫자 출력");
    		System.out.println(param);
    	}
    	public void a(String param) {
    		System.out.println("문자 출력");
    		System.out.println(param);
    	}
    
    	
    	public static void main(String[] args) {
    		polymorphism0 o = new polymorphism0();
    		o.a(1);
    		o.a("one");
    		
    	}
    
    }
    ```
    
- 클래스와 다형성
    - 어떠한 class를 인스턴스화 시킬 때, 인스턴스를 담는 변수의 데이터 타입은 인스턴스화 시킨 class가 될 수도 있고, 해당 class의 부모 class가 될 수도 있다.
    - 이와 같은 방법을 사용하는 효과는 인스턴스 변수가 인스턴스화 한 class가 아닌, 해당 class의 부모 class로 여겨진다는 점에 있다.
    - 하지만 오버라이딩이 진행된다면, class B를 인스턴스화 한 obj가 A의 행세를 하더라도, x를 출력할 경우 class B에 소속되어 있는 method x를 실행된다.
        
        ```java
        package Project.polymorphism;
        class A{
        	public String x() {return "A.x";}
        }
        class B extends A{
        	public String x() {return "B.x";}
        	public String y() {return "y";}
        }
        public class polymorphism1 {
        
        	public static void main(String[] args) {
        		A obj = new B();
        		System.out.println(obj.x());
        	}
        
        }
        ```
        
    - 따라서, 위 코드의 출력 값은 B.x 가 된다.
- 클래스와 다형성 2
    
    ```java
    package Project.polymorphism;
    class A{
    	public String x() {return "A.x";}
    }
    class B extends A{
    	public String x() {return "B.x";}
    	public String y() {return "y";}
    }
    class B2 extends A{
    	public String x() {return "B2.x";}
    }
    public class polymorphism1 {
    	public static void main(String[] args) {
    		A obj = new B();
    		A obj2 = new B2();
    		System.out.println(obj.x());
    		System.out.println(obj2.x());
    	}
    
    }
    ```
    
    - 위 코드의 출력값은 아래와 같다.
    B.x
    B2.x
    - 이는 obj, obj2가 A 자료형을 참조하더라도, 실제 인스턴스 함수는 B이기 때문이다.
    - 즉, 동일한 데이터 타입으로 존재하면서 각각의 class에 정의되어 있는 method를 호출할 때는, 그 각각의 class에 소속되어 있는 method에 정의되어 있는 방식대로 동작하는 것이 바로 다형성의 개념이다.
- 실전 예제
    
    ```java
    package Project.polymorphism;
    
    abstract class Calculator{
        int left, right;
        public void setOprands(int left, int right){
            this.left = left;
            this.right = right;
        } 
        int _sum() {
            return this.left + this.right;
        }
        public abstract void sum();  
        public abstract void avg();
        public void run(){
            sum();
            avg();
        }
    }
    class CalculatorDecoPlus extends Calculator {
        public void sum(){
            System.out.println("+ sum :"+_sum());
        }
        public void avg(){
            System.out.println("+ avg :"+(this.left+this.right)/2);
        }
    } 
    class CalculatorDecoMinus extends Calculator {
        public void sum(){
            System.out.println("- sum :"+_sum());
        }
        public void avg(){
            System.out.println("- avg :"+(this.left+this.right)/2);
        }
    } 
    public class polymorphism2 {
    	public static void execute(Calculator cal) {
    		System.out.println("실행결과");
    		cal.run();
    	}
        public static void main(String[] args) { 
            Calculator(DecoPlus) c1 = new CalculatorDecoPlus();
            c1.setOprands(10, 20);
            c1.run();
             
            Calculator(DecoMinus) c2 = new CalculatorDecoMinus();
            c2.setOprands(10, 20);
            c2.run();
        } 
    }
    // 출력값 
    + sum :30
    + avg :15
    - sum :30
    - avg :15
    ```
    
    - 상속 개념을 이용해 main method의 데이터타입을 부모 클래스인 Calculator로 사용할 수 있다. 이를 이용해서, 서로 다른 클래스를 담은 인스턴스 c1, c2가 같은 데이터타입을 가지고 있게 할 수 있다. 하지만 데이터 타입이 같을 뿐, run method를 통해 동작하는 구체적인 구현은 다르다.
    - 따라서, polymorphism2  class에서 execute method를 통해 실행된 결과는 cal을 매개변수로 각각의 c1, c2의 Argument를 전달받기 때문에, cal.run을 통해 서로 다른 데이터 타입이라고 해도, 부모 클래스에 규정되어 있는 동일한 method를 실행 가능하게 해주는 것이다.
    - 만약 다향성의 개념을 사용하지 않는다면,  polymorphism2  class의 parameter를 각각 CalculatorDecoPlus, CalculatorDecoMinus로 받는 method를 2개 생성해야 했을 것이다.  (혹은, 매개변수를 사용하거나)
    - 이와 같은 특징, 효율성이 다형성의 장점 중 하나라고 볼 수있다.
- 인터페이스와 다형성 1
    
    ```java
    package study;
    interface I2{
    	public String A();
    }
    interface I3{
    	public String B();
    }
    class D implements I2, I3{
    	public String A() {
    		return "A";
    	}
    	public String B() {
    		return "B";
    	}
    }
    public class Polymorphism2 {
    	public static void main(String[] args) {
    		D obj = new D();
    		I2 objI2 = new D();
    		I3 objI3 = new D();
    		
    		obj.A();
    		obj.B();
    		
    		objI2.A();
    		//objI2.B();
    		
    		//objI3.A();
    		objI3.B();
    	}
    }
    ```
    
    ![8.png](JAVA%20Polymorphism%20fa61712e43454e69b9c83da2b310fedb/8.png)
    
    - 위 code의 정의 관계는 위와 같다.
    - D라고 하는 클래스에 데이터 타입으로 I2와 I3라는 인터페이스를 사용한다는 것은 그 클래스가 인터페이스에서 정의하고 있는 멤버들(A method와 B method)만을 가지고 있는 클래스인 것처럼 사용할 수 있다.
    - 즉, 어떤 클래스가 두 개의 인터페이스를 사용하고 있다는 것은, 각각의 인터페이스들이 인터페이스로 그룹핑되어 있는 메소드나 필드들의 집합이라는 의미다.
    - 이러한 맥락에서 D라는 클래스를 사용할 때, 그 클래스가 가지고 있는 모든 기능(method)를 사용해야 한다면
    D obj = new D();
    위와 같은 형식을 이용, obh에 D라는 클래스를 데이터 타입으로 지정해야 하지만
    - I2라는 인터페이스에 해당하는 기능들만 사용하고 싶을 때는 
    I2 objI2 = new D();
    위와 같은 형식으로 지정해야 한다는 의미다.
    - 이 때, 데이터 타입으로 특정 인터페이스만을 정의하는 것은, 그 인터페이스에서 지정하고 있는 멤버 (A method)를 제외한 멤버(B method)는 존재하지 않는 것으로 여겨진다고 생각할 수 있다.
- 인터페이스와 다형성 2
    - 다형성의 핵심은 상속과 맞물린다. 여러 멤버들 중에서 원하는 method만을 골라서 사용하고, 하나의  레퍼런스 변수로 원하는 method만을 호출할 수 있는. 유지보수성의 극대화.
    
    ```java
    package study;
    
    interface father{}
    interface mother{}
    interface programmer{
    	public void coding();
    }
    interface believer{}
    class Steve implements father, programmer, believer{
    	public void coding() {
    		System.out.println("fast");
    	}
    }
    class Rachel implements mother, programmer{
    	public void coding() {
    		System.out.println("elegance");
    	}
    }
    public class Polymorphism3{
    	public static void main(String[] args[]) {
    		programmer employee1 = new Steve();
    		programmer employee2 = new Rachel();
    		
    		employee1.coding();
    		employee2.coding();
    	}
    }
    ```
    
    - Polymorphism3 라는 class에서 원하는 method인 coding method만을 추려 쓰고 싶을 때. Steve와 Rachel이 가지고 있는 여러 interface 중에서, 원하는 기능이 있는 programmer interface를 레퍼런스 변수로 규정함으로서, 해당 method만을 뽑아서 사용할 수 있는 것이다.