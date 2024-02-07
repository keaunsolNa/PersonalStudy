package com.greedy.section01.polymorphism;

public class Application1 {

	public static void main(String[] args) {
		System.out.println("Animal 생성 ~~~~~~~~~~~~~~~~~~~~~");
		Animal animal = new Animal();
		animal.eat();
		animal.run();
		animal.cry();
		
		System.out.println("Rabbit 생성 ~~~~~~~~~~~~~~~~~~~~~");
		Rabbit rabbit = new Rabbit();
		rabbit.eat();
		rabbit.run();
		rabbit.cry();
		rabbit.jump();
		
		System.out.println("Tiger 생성 ~~~~~~~~~~~~~~~~~~~~~");
		Tiger tiger = new Tiger();
		tiger.eat();
		tiger.run();
		tiger.cry();
		tiger.bite();
		
		/* 다형성 적용 */
		/*
		 * Rabbit과 Tiger는 Animal 클래스를 상속 받았다.
		 * 따라서 Rabbit은 Rabbit 타입이기도 하면서 Animal 타입이기도 하며
		 * Tiger 역시 Tiger 타입이면서 Animal 타입이기도 하다.
		 */
		Animal a1 = new Rabbit();
		Animal a2 = new Tiger();
		
//		Rabbit r = new Animal();
//		Rabbit t = new Animal();
		
		System.out.println("동적 바인딩이 적용된 것");
		a1.cry();
		a2.cry();
		
		/* 현재 레퍼런스 변수의 타입은 Animal이기 때문에 자신이 가지지 않은 메소드는 동작시키지 못한다. 
		 * (정적바인딩으로 판단하기 때문에)
		 */
//		a1.jump();
//		a2.bite();
	
		System.out.println("클래스 타입 형변환 ~~~~~~~~~~~~~~~~~~~~~~~");
		
		/*
		 * 객체별로 고유한 기능을 동작시키기 위해서는 레퍼런스 변수를 형변환하여
		 * Rabbit과 Tiger로 변경해야 메소드 호출이 가능하다.
		 * 
		 * Class Type Casting : 클래스 형변환
		 */
		((Rabbit)a1).jump();
		((Tiger)a2).bite();
		
		/*
		 * 클래스 형변환을 잘못 하는 경우
		 * 컴파일 시에는 문제가 생기지 않지만 런타임 시 ClassCastException이 발생한다.
		 */
//		((Tiger)a1).bite();
		
		/*
		 * 레퍼런스 변수가 참조하는 실제 인스턴스가 확인을 원하는 타입과 일치하는지 비교하는 연산자
		 * instanceof를 활용
		 */
		System.out.println("instanceof 확인~~~~~~~~~~~~~~~~~~~~~~~");
		System.out.println("a1이 Tiger 타입인지 확인: " + (a1 instanceof Tiger));
		System.out.println("a1이 Rabbit 타입인지 확인: " + (a1 instanceof Rabbit));

		/* 상속 받은 타입도 함께 가지고 있다. */
		System.out.println("a1이 Animal 타입인지 확인: " + (a1 instanceof Animal));
		
		/* 모든 클래스는 Object의 후손이다. */
		System.out.println("a1이 Object 타입인지 확인: " + (a1 instanceof Object));
		
		/* instanceof 연산자를 이용해서 해당 타입이 맞는 경우만 해당 타입으로 클래스 형변환을 적용하자. */
		
		if(a1 instanceof Rabbit) {
			((Rabbit)a1).jump();
		}
		
		if(a1 instanceof Tiger) {
			((Tiger)a1).bite();
		}
		
		/*
		 *  클래스 형변환은 up-casting과 down-casting으로 구분할 수 있다.
		 *  up-casting : 상위 타입으로 형변환
		 *  down-casting : 하위 타입으로 형변환
		 */
		Animal animal1 = (Animal) new Rabbit();		
		
		/* 묵시적 형변환 */
		Animal animal2 = new Rabbit();					// up-casting은 묵시적 형변환 가능(다형성)
		
		/* 명시적 형변환 */
		Rabbit rabbit1 = (Rabbit) animal1;
//		Rabbit rabbit2 = animal2;						// down-casting은 묵시적 형변환 불가능(다형성이 적용되지 않는다.)
		
	
	}
}
