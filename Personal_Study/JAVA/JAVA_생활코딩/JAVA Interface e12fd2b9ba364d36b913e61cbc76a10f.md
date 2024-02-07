# JAVA Interface

# 오리엔테이션

- 1.  수업소개
    - 인터페이스란 자유를 위한 규제라고 할 수 있다.
    - 전기의 많은 형태들과 무수한 제품을 플러그와 콘센트라는 규격으로 묶은 것처럼, 규제를 한 것처럼. 이 규제가 곧 인터페이스라고 할 수 있다.
    - 프로그래밍 언어의 개념으로 접근한다면, interface를 통해 class는 interface를 통해 규정한 형태로 지정되어야 한다는 의미가 된다.
    
    ```java
    interface Calculable{
    int sum(int v1, int v2);
    }
    class RealCal implements Calculable{
    	public int sum(int v1, int v2) {
    		return v1+v2;
    	}
    }
    public class InterfaceApp {
    	public static void main(String[] args) {
    		RealCal c = new RealCal();
    		System.out.println(c.sum(2, 1));
    	}
    }
    ```
    
    - 위의 코드의 경우, 앞서 Calculable이라는 이름의 interface는 int 자료형의 sum이라는 변수명으로 int 자료형을 2개 받는 method가 정의되었기에.
    - 후속 class인 RealCal이 implements 연산자를 통해 Calculable interface를 규정되었기에, sum이라는 변수명이 변경되거나, sum 안의 시그니처 값이 변경된다면 컴파일 에러가 발생하게 된다. 
    이는, RealCal class가 Calculable class의 규격을 지키지 않았기 때문이다.

# Interface

- 2.  인터페이스의 형식
    - 하나의 class에 하나의 상속만 받을 수 있는 것과는 달리, Java는 여러 개의 인스턴스를 구현할 수 있기에, Interface는 여러 개 구현(implements) 될 수 있다.
    - Interface에 method를 정의할 때는 형태, 시그니쳐만 들어가고 그 값은 정의되지 않는다.
    - 반면, Interface에 변수를 정의할 때는 리터럴 값이 들어가야 한다.
        
        ```java
        interface Calculable{
        	double PI = 3.14;
        	int sum(int v1, int v2);
        }
        interface Printable {
        	void print();
        }
        class RealCal implements Calculable, Printable{
        	public int sum(int v1, int v2) {
        		return v1+v2;
        	}
        	public void print() {
        		System.out.println("This is RealCal!!");
        	}
        }
        public class InterfaceApp {
        	public static void main(String[] args) {
        		RealCal c = new RealCal();
        		System.out.println(c.sum(2, 1));
        		c.print();
        		System.out.println(c.PI);
        	}
        }
        ```
        
    - 위 코드의 경우, RealCal class는 Calculabe interface와 Printable interface 두 개를 구현한 것.
    - 마찬가지로, 구현된 두 개의 interface의 형식을 모두 지켜야 한다.
    - 또한, 비교적 덜 사용하긴 하지만, interface에는 변수도 정의 될 수 있는데, 이 때 변수는 method와 달리 변수의 리터럴 값이 interface에서 정의된다. 해당 interface를 구현한 class의 경우, method와 달리 형태를 삽입하지 않아도 된다. 다만, 해당 method 안에서 해당 변수 (PI)의 값(3.14)이 정의되어 있는 것이다.
- 3.  다형성(Polymorphism)
    - 어떠한 class가 데이터 타입을 무엇으로 하느냐에 따라 다양한 기능을 발휘하게 하는 것이 바로 다형성.
    - ClassName Varname = new Classname();
    으로 규정되는 class 호출과 달리,
    InterfaceName Varname = new Classname();의 형식으로 지정함으로, 
    해당 class에 있는 여러 Interface 중에 하나를 선택해 규정할 수 있다.
    
    ```java
    interface Calculable{
    	double PI = 3.14;
    	int sum(int v1, int v2);
    }
    interface Printable {
    	void print();
    }
    class RealCal implements Calculable, Printable{
    	public int sum(int v1, int v2) {
    		return v1+v2;
    	}
    	public void print() {
    		System.out.println("This is RealCal!!");
    	}
    }
    class AdvancedPrint implements Printable {
    	public void print() {
    		System.out.println("This is RealCal!!");
    	}
    }
    public class InterfaceApp {
    	public static void main(String[] args) {
    		Printable c = new AdvancedPrint();
    		c.print();
    	}
    }
    ```
    
    - new 연산자 뒤의 class의 종류를 앞의 인터페이스를 통해 지정하면, 같은 인터페이스를 공유하는 어떤 class라도 올 수 있다. 즉, 호환성을 보장한다.
- 4.  사용설명서 속의 인터페이스
    - FileWriter라는 method는 AutoCloseable 라는 method를 포함하며, AutoCloseable은 .close() 라는 method를 강제한다. FileWriter는 입력을 하는 method인데, 입력이 진행 중일 때 또 다른 입력을 시작하기 전에 .close()를 통해 작업을 끝낸 뒤 다른 입력을 시작하게 만드는 것.
    - 인터페이스는 이와 같은 용도로 쓰인다. 어떠한 method에 반드시 필요한 작업이나, 서로간의 규격을 통일하기 위해, 강제로 해당 method, class 등을 사용하게 만드는 것.  220v짜리 제품에 110v제품을 꽂지 않도록.
- 5.  수업을 마치며
    - Interface는 어려운 개념이기에 지금 당장 사용하려 하지는 말자. 다만 다른 사용자들의 Interface를 유심히 보고, Interface를 사용해야 하는 순간이 올 때를 대비하자.