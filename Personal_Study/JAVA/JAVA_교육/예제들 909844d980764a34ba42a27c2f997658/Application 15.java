package com.greedy.section01.erxtend;

public class Application {
	public static void main(String[] args) {
		
		  /*
	       * 상속(inheritance)
	       * 상속은 현실세계의 상속과 비슷한 개념이다.
	       * 부모가 가진 DNA(자바에서는 클래스가 가지는 필드나 메소드 = 멤버(생성자는 아님))을 자식이
	       * 물려 받는다는 의미이다.
	       * 클래스 또한 부모 클래스와 자식 클래스로 역할을 나누어서 부모가 가지는 멤버를 자식이 물려받아
	       * 자신의 멤버인 것처럼 사용할 수 있도록 만든 기술이다.
	       * 
	       * 하지만 단순히 물려받는 개념보다 조금 더 나아간다면
	       * 자바에서의 상속은 부모 클래스를 확장(extends)한다는 개념을 가진다.
	       * 물려 받아서 자신의 것처럼 사용하는 것 뿐만 아니라 추가적인 멤버도 작성이 가능하다.
	       * 특히 메소드 재정의(overriding)라는 기술을 이용해서 부모가 가진 메소드를 재정의 하는 것도 가능하다.
	       * 
	       * 메소드 재정의(overriding)이란 부모가 가지는 메소드 선언부를 그대로 사용하면서
	       * 자식 클래스가 정의한 메소드 대로 동작하도록 구현 몸체({}) 부분을 새롭게 다시 작성하는 기술이다.
	       * 메소드 재정의를 하면 메소드를 호출할 시 재정의 한 메소드가 우선적으로 동작하게 된다.
	       * 
	       * 이러한 상속이라는 기술을 사용하게 되면 얻게되는 이점은 크게 두 가지로 볼 수 있다.
	       * 1. 새로운 클래스를 작성할 시 기존에 작성한 클래스를 재사용할 수 있다.
	       *      1-1. 재사용 시 생산성을 크게 향상시킬 수 있다.(새롭게 작성하는 것보다 빠르다.)
	       *    1-2. 공통적으로 사용하는 코드가 부모 클래스에 존재하면 수정 사항이 생길 시 부모 클래스만 수정해도
	       *         전체적으로 적용된다.(유지보수성 증가)
	       * 2. 클래스 간의 계층관계가 형성되며 다형성의 문법적 토대가 된다.
	       * 
	       * 하지만 상속으로 인한 단점도 존재한다.
	       * 1. 부모 클래스의 기능을 추가/변경할 시 자식 클래스가 정상적으로 동작하는지에 대한 예측이 힘들다.
	       *    상속 구조가 복잡해 질 수록 그 영향에 대한 예측이 힘들며 이런 단점이 유지보수성을 증가 시킨다는 장점과는 
	       *    반대로 유지보수에 악영향을 미친다.
	       * 2. 또한 부모 클래스의 변경 또한 쉽지 않다. 자식 클래스에게 중요하게 사용하는 기능인 경우
	       *    부모 클래스를 변경할 시 자식 클래스에 모두 영향을 줄 수 있다.
	       *    역시 유지보수에 악영향을 미친다.
	       * 3. 부모 클래스에서는 의미 있었던 기능이 자식 클래스에서는 무의미 할 수 있다. (불필요한 기능을 물려 받음)
	       * 
	       * 장점과 단점을 고려했을 때,
	       * 상속은 재사용이라는 장점만 바라보게 되면 오용의 가능성이 있기 때문에 유지보수에 좋지 않은 코드를
	       * 작성 할 확율이 높다.
	       * 상속은 IS-A관계로 구분되는 경우에만 사용해야 한다.
	       * 
	       * Shape is a Circle.      (x)
	       * Circle is a Shape.      (o)            상속관계 : Shape(부모) - Circle(자식) 
	       * 
	       * Shape has a Circle.      (x)
	       * Circle has a Shape.      (x)
	       * 
	       * Circle has a point.      (o)            연관관계 : Circle - point(필드변수)
	       * point has a Circle.      (x)
	       */
		
		Car car = new Car();
		
		car.soundHorn();
		car.run();
		car.soundHorn();
		car.stop();
		car.soundHorn();
		System.out.println();
		
		FireCar fireCar = new FireCar();
		
		fireCar.soundHorn();
		fireCar.run();
		fireCar.soundHorn();
		fireCar.stop();
		fireCar.soundHorn();
		fireCar.sprayWater();
		System.out.println();
		
		RacingCar racingCar = new RacingCar();
		
		racingCar.soundHorn();
		racingCar.run();
		racingCar.soundHorn();
		racingCar.stop();
		racingCar.soundHorn();
	}

}
