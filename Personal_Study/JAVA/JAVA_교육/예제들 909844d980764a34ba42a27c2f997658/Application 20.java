package com.greedy.section01.generic;

public class Application {
	public static void main(String[] args) {

		/* 제네릭(Generic) */
		/*
		 * 제네릭의 사전적인 의미는 일반적인이라는 의미이다.
		 * 자바에서 제네릭이란 데이터의 타입을 일반화한다는 의미를 가진다.
		 * 
		 * JDK 1.5버전부터 추가된 문법
		 * 
		 * 제네릭 프로그래밍
		 * 데이터의 형식에 의존하지 않고 하나의 값이 여러 다른 데이터 타입들을 가질 수 있는
		 * 기술에 중점을 두어 재사용성을 높일 수 있는 프로그래밍 방식이다.
		 * 
		 * 제네릭의 이점
		 * 1. 구현의 편리함(하나의 클래스만 작성해도 여러 타입을 다룰 수 있음)
		 * 2. 자료형의 안전(자료형이 맞지 않으면 컴파일 에러를 발생 시켜 줌)
		 */
		
		/* 제네릭 클래스로 객체를 만들 때 원하는 타입(<>안의 타입)으로 정의된 제네릭 객체가 된다. */
		/* 1. 타입을 Integer로 인스턴스를 생성하는 경우 */
		GenericTest<Integer> gt = new GenericTest<Integer>();
		
		/* 2. 타입을 Double로 인스턴스를 생성하는 경우 */
		GenericTest<Double> gt2 = new GenericTest<Double>();
		
		/* 메소드 인자 및 반환값 모두 Integer 타입인 것을 확인해 보자. */
		gt.setValue(new Integer(10));
//		gt.setValue(new Double(10));						// 제네릭 클래스의 T가 Integer로 변했기 때문에 컴파일 에러 발생
		System.out.println(gt.getValue());
		Integer int1 = gt.getValue(); 
		
		/* 메소드 인자 및 반환값 모두 Double 타입인 것을 확인해 보자. */
		gt2.setValue(new Double(10.0));
		System.out.println(gt2.getValue());
		Double double1 = gt2.getValue();

		/* 3. 타입을 String으로 인스턴스를 생성하는 경우 */
		/*
		 * JDK 1.7부터 타입 선언 시 타입변수가 작성되면 타입 추론이 가능하기 대문에
		 * 생성자 쪽의 타입을 생략하고 빈 다이아몬드 연산자를 사용해도 된다.
		 */
		GenericTest<String> gt3 = new GenericTest<>();
		
		gt3.setValue("홍길동");
		System.out.println(gt3.getValue().charAt(0));
		System.out.println(gt3.getValue() instanceof String);
		
		ObjectTest obj = new ObjectTest();
		obj.setValue("홍길동");
		System.out.println(((String)obj.getValue()).charAt(0));
	
	}

}
