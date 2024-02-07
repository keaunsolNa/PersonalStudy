package com.greedy.section01.method;

public class Application2 {

	public static void main(String[] args) {
		
		/* 메소드 호출 흐름 연습 */
		System.out.println("main() 시작함...");
		
		Application2 app2 = new Application2();
		app2.methodA(); 	
		app2.methodB();
		app2.methodA();
		
		
		System.out.println("main() 종료 됨...");
	}
	public void methodA() {
		System.out.println("methodA()호출 됨...");
		
		System.out.println("methodA()호출 됨...");
		
	}
	public void methodB() {
		System.out.println("methodB()호출 됨...");
		System.out.println("methodB()호출 됨...");
		
	}
	
	public void methodC() {
		System.out.println("methodC()호출 됨...");
		System.out.println("methodC()호출 됨...");
	
		
	}
}
