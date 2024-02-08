# JAVA 상속

# 오리엔테이션

- 1.  수업 소개
    - 하나의 class의 모든 method와 변수를 포함한, 확장된 class가 상속의 개념.
    - 확장의 목적은 유지보수의 편의성, 재사용성, 가독성을 높이고 코드의 양을 줄이는데 있다.
    - 상속의 선언은 아래와 같다.
    
    ```java
    class Cal {
    public int sum(int v1, int v2) {
    return v1+v2'
    }
    }
    
    class Cal2 extends Cal{
    ]
    ```
    
    - 아래의 extends를 통해, Cal2는 Cal을 상속한다.
    - 따라서, Cal2의 범위 안에서 아무런 method나 변수가 없더라도,
    - main method에서 Cal2의 sum method를 불렀을 때,
    - 상속한 Cal class에 있는 sum method를 불러와 적용시키는 개념이 바로 상속.
- 6.  수업을 마치며
    - 다형성(Polymorphism) : 자식 클래스를 부모 클래스로서 동작하게 규제하는 테크닉.
    - 접근 제어자 (Access Modifiers) : Method, Class, 변수를 정해진 범위 안에서만 작동하도록 제어하는 테크닉. public, default, protected, private.
    - Final : class를 상속, method를 overriding, 변수의 수정을 아무나 하지 못 하게 하도록 만드는 테크닉.
    - Abstract : 상속자를 상속하려는 사용자에게 특정 method를 강제적으로 사용하게 만드는 테크닉.

# 상속(Inheritance)

- 2.  기능의 개선과 발전
    - 이전의 상속 개념은 같은 기능만을 출력하고 있기에, 의미가 있는 코드라고 보기는 힘들다.
    - 확장된 코드는 부모의 method 외의, 다른 method나 변수를 추가하는 것은 물론, 부모의 method나 변수를 재정의할 수도 있다. 이 때, 이 재정의를 Overriding이라고 한다.
    
    ```java
    class Cal{
    	public int sum(int v1, int v2) {
    		return v1+v2;
    	}
    }
    class Cal3 extends Cal {
    	public int minus(int v1, int v2) {
    		return v1-v2;
    	}
    	// Overriding
    	public int sum(int v1, int v2) {
    		System.out.println("Cal3!!");
    		return v1+v2;
    	}
    }
    ```
    
    - method의 추가와 method 재정의, Overriding의 방식은 위와 같다.
    - Cal을 상속한 Cal3는 minus라는 이름의 method를 추가했으며, 기존의 Cal class에 있는 sum method를 Overriding한 것.
    - 이와 같은 확장성과 재정의가 상속의 가장 근복적인 존재이유라 할 수 있다.
    
- 3.  Overriding Vs Overloading
    
    ```java
    public int sum(int v1, int v2) {
    		return v1+v2;
    	}
    	//Overloading
    	public int sum(int v1, int v2, int v3) {
    		return v1+v2+v3;
    	}
    ```
    
    - Overriding 은 상속받은 부모의 method에 올라타서 재정의 한 것. 덮어 쓴 것. 기존의 개념에 추가된게 아니라, 재정의 한 것에 가깝다. 따라서, 시그니쳐가 같아도 Overriding이 가능하다.
    - Overloading의 Overload는 과적의 의미가 있다.  Java의 method는 동일한 method name으로 여러 method를 만들 수, 과적할 수 있는데. 이 때의 기능을 Overloading이라고 한다. 따라서, Overloading은 상속에 포함된 개념은 아니다.
    
- 4.  this & super
    
    ```java
    class Cal{
    	public int sum(int v1, int v2) {
    		return v1+v2;
    	}
    	//Overloading
    	public int sum(int v1, int v2, int v3) {
    		return this.sum(v1, v2)+v3;
    	}
    }
    class Cal3 extends Cal {
    	public int minus(int v1, int v2) {
    		return v1-v2;
    	}
    	// Overriding
    	public int sum(int v1, int v2) {
    		System.out.println("Cal3!!");
    		return super.sum(v1, v2);
    	}
    }
    ```
    
    - 상속에서의 this는 자기 자신, 같은 class의 다른 생성자를 가리킨다.  위 코드에서의 this가 가리키는 것은 Cal class에서의 sum method인 것.
    - 그렇기에  this.sum(v1, v2) + v3의 의미는 Cal class의 sum method를 실행, return 값인 v1+v2를 받은 뒤, 그 값에 v3를 더하게 되는 것이다.
    - 반면, super의 경우. super는 그 자체로 해당 class의 부모 클래스를 의미한다.
    - 그렇기에 super.sum(v1,v2)는 Cal3가 아닌, Cal3의 부모 클래스인 Cal 클래스를 가리키게 되는 것이며, Cal class의 sum method인 v1+v2가 실행된다.
- 5.  상속과 생성자
    
    ```java
    package inheritance;
    
    class Cal{
    	int v1,v2;
    	Cal(int v1, int v2) {
    		System.out.println("Cal init");
    		this.v1 = v1; this.v2 = v2;
    	}
    	public int sum() {return this.v1+v2;}
    }
    class Cal3 extends Cal {
    
    	Cal3(int v1, int v2) {
    		super(v1, v2);
    		System.out.println("Cal3 init");
    	}
    	public int minus() {return this.v1-v2;}	
    }
    public class Java_Inheritance {
    	public static void main(String[] args) {
    		Cal c = new Cal(2,1);
    		Cal3 c3 = new Cal3(2, 1);
    		System.out.println(c3.sum());		//3
    		System.out.println(c3.minus());		//1
    	}
    }
    ```
    
    - 상속 시, 부모 Class에 생성자가 있다면 계승받은 클래스는 반드시 생성자를 실행할 수 있도록 상속자를 만들어줘야 한다. 따라서
    	Cal3(int v1, int v2) {
    		super(v1, v2);
    	}
    위 처럼 생성자를 작성, super를 통해 부모클래스의 변수값을 지정하지 않는다면 컴파일 에러가 발생한다.
    - 다만, 이는 매개변수가 있는 생성자이기에 생성자 추가가 필요한 것이지, 매개변수가 없다면 에러 없이 실행 가능하다. (JVM이 자동으로 생성해줌.)
    - 하지만 this와 마찬가지로, 명시적 표현을 위해 생성자를 추가해주도록 하자.