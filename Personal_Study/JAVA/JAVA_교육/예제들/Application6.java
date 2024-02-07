package com.greedy.section01.method;

public class Application6 {
	public static void main(String[] args) {
		
		/* 메소드 리턴값 테스트 */
		System.out.println("main() 메소드 시작함...");
		
		Application6 app6 = new Application6();
		
		/* 메소드의 반환값을 여러번 활용할 경우에는 변수에 담는게 좋다. */
		String returnText = app6.testMethod();
		System.out.println(returnText);				
		
		/* 한번만 호출해서 반환값을 활용 할 경우에는 따로 변수에 안 담아도 좋다. */
		System.out.println(app6.testMethod());		
		
		System.out.println("main() 메소드 종료 됨...");
	}
	
	public String testMethod() {
		return "Hello world";
	}
}
