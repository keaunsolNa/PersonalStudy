package com.greedy.section03.api.math;

public class Application1 {
	public static void main(String[] args) {
	
		/* 
		 * API
		 * Application Programming Interface를 줄인 말로
		 * 응용프로그램에서 사용할 수 있도록 운영체제나 프로그래밍 언어가 제공하는
		 * 기능을 제어할 수 있도록 만든 인터페이스이다.
		 * 
		 */
		
		/* java.lang.Math */
		/* Math 클래스는 수학에서 자주 사용하는 상수들과 함수들을 미리 구현해 놓은 클래스이다. */
		
		/* 절대값 */
		System.out.println("-7의 절대값: " + java.lang.Math.abs(-7));
		
		/* 메소드를 굳이 쓰지 않더라도 우리가 알고리즘을 짜서 기능하게 할 수 있다. */
		int input = -7;
		System.out.println("-7의 절대값: " + (input < 0 ? -input : input));
		
		/*
		 * 원래는 다른 패키지의 클래스를 쓸 때 패키지명을 생략하고 싶으면
		 * import를 써야 한다.
		 * 하지만 java.lang 패키지는 import를 쓰거나 클래스명을 쓰지 않아도 된다. (유일하게)
		 */
		System.out.println("-1.25의 절대값 : " + Math.abs(-1.25));
		
		/* 최소, 최대값 */
		System.out.println("10과 20중에 최소값은 : " +Math.min(10, 20));
		System.out.println("10과 20중에 최대값은 : " +Math.max(10, 20));
		
		/* 
		 * 수학적으로 많이 사용되는 고정된 값(상수)들도 이미 Math 안에 정의된 것이 있다.
		 * 필드라는 것을 이용한 것인데 이 부분은 나중에 다뤄보자.
		 */
		System.out.println("원주율: " + Math.PI);
		
		System.out.println("난수 발생: " +  Math.random());
		System.out.println("난수 발생: " +  (int)(Math.random()*10));
		
	}

}
