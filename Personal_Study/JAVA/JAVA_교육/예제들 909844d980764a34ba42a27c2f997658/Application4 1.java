package com.greedy.section01.method;

public class Application4 {

	public static void main(String[] args) {
	
		/* 여러 개의 전달인자를 이용한 메소드 호출 테스트 */
		Application4 app4 = new Application4();
		app4.testMethod("홍길동", 22, '남');				// 매개변수 있는 메소드를 호출할 때는 전달인자의 자료형과 순서에 유의하자. 
		app4.testMethod("홍길동", 22, '여');				// 라이프 사이클(스코프). 첫 번째 argument가 parameter로 던져진 뒤, parameter는 종료되고, 다시 argument를 호출한다.
		
		/* 변수에 저장된 값을 매개변수로 전달하며 메소드 호출(가독성이 좋음) */
		String name = "유관순";
		int age = 21;
		char gender = '여';
		
		app4.testMethod(name, age, gender);
	}
	
	public void testMethod(String name, int age, final char GENDER) {
		
		name = "이순신";
		age = 33;
//		GENDER = '여';			// final로 선언된 매개변수(상수)에는 값이 대입될 수 없다. 
		
		System.out.println("당신의 이름은 " + name + "이고, 나이는" 
							+ age + "세이며, 성별은 " + GENDER + "자입니다.");
	}
}
