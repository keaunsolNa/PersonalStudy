package com.greedy.section01.method;

public class Application3 {
	public static void main(String[] args) {
		
		/* 전달인자(argument)와 매개변수(parameter)를 이용한 메소드 호출 */
		/*
		 * 지금까지 우리가 배운 변수는 지역변수에 해당한다.
		 * 다양한 변수의 종류들이 존재 하지만 차차 배워 나가자
		 * 
		 * 변수의 종류
		 * 1. 지역변수
		 * 2. 매개변수
		 * 3. 전역변수(필드)
		 * 4. 클래스(static) 변수
		 * 
		 * 지역변수는 선언한 메소드 블럭 내부에서만 사용이 가능하다
		 * 이것을 지역변수의 스코프(scope)라고 한다.
		 * 다른 메소드 간 서로 공유해야 하는 값이 존재하는 경우 메소드 호출 시
		 * 사용하는 괄호를 이용하여 값을 전달할 수 있다.
		 * 이 때 전달하는 값을 전달인자(argument)라고 부르고,
		 * 메소드 선언부 괄호 안에 전달 인자를 받기 위해 선언하는 변수를
		 * 매개변수 (parameter)라고 부른다.
		 */
		
		Application3 app3 = new Application3();
		
		/* 전달인자 30으로 값 전달 테스트 */
		app3.testMethod(30);						// 30 : argument
		
		/* 변수에 저장한 값 전달 테스트 */
		int age = 20;
		app3.testMethod(age);
		
		/* 자동형변환을 이용하여 값을 전달할 수 있다. */
		byte byteAge = 10;		
		app3.testMethod(byteAge);
		
		/* 강제형변환을 이용하여 값을 전달할 수 있다. */
		long longAge = 80;
		app3.testMethod((int)longAge);
		
		/* 연산의 결과를 이용해서 값 전달을 할 수도 있다. */
		app3.testMethod(age * 3);
		
	}	// main end
	
	public void testMethod(int age) {				// age : parameter이자 지역변수
		System.out.println("당신의 나이는 " + age + "세 입니다.");
	}

}
