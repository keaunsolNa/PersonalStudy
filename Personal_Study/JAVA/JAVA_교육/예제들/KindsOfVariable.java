package com.greedy.section07.kindsofvariable;

public class KindsOfVariable {
	
	/* 
	 * static field를 정적필드 (클래스 변수, static변수)라고 한다.
	 * (정적(클래스) 영역(static)에 생성되는 변수라는 의미)
	 */
	private static int staticNum;
	
	/* non-static field를 인스턴스 변수라고 한다. (인스턴스 생성 시점에 사용 가능한 변수) */
	private int globalNum;						// 클래스의 필드에 선언한 변수는 필드 변수이자 전역 변수라고 부른다.

	public void testMethod(int num) {
		
		/* 
		 * 메소드 영역에서 작성하는 변수를 지역변수라고 한다.
		 * 메소드의 괄호 안에 선언하는 변수는 매개변수이고
		 * 매개변수도 일종의 지역변수이다.
		 * 지역변수와 매개변수는 모두 메소드 호출 시 stack에 할당받아 생성된다.
		 */
		
		int localNum;

		System.out.println(num);				// 전달인자를 주면서 메소드를 호출할 것이므로 값이 넘어와서 num에 대입되기 때문에 따로 초기화가 필요 없다.
//		System.out.println(localNum);			// 매개변수가 아닌 지역변수는 반드시 초기화가 되어야 한다. (JVM이 초기화해주지 않는다.)
		System.out.println(this.globalNum);		// 전역변수(인스턴스변수)는 JVM이 초기화 해 준다. (인스턴스가 생성될 때)
		System.out.println(staticNum);			// 전역변수(클래스변수)는 JVM이 초기화 해 준다. (클래스가 로드 될 때 == 프로그램 실행 시)
		
		/* 
		 * 클래스 로딩 과정
		 * 클래스 로더(Class Loader)가 .class 클래스 파일(컴파일 된 소스파일)의 위치를 찾아
		 * 메소드 영역(== 클래스 영역 \\ static 영역)에 올려놓는 과정을 뜻한다.
		 * JVM을 실행할 때 (== 프로그램 실행 시) 이미 클래스 파일들을 따로 호출하지 않아도 메소드 영역에 로딩 된다.
		 */
	}
	
	/* 전역 변수와 지역변수의 차이를 확인 할 메소드를 추가하고 접근해 보자 */
	public void testMethod2() {
//		System.out.println(localNUm);			// 지역 변수는 블럭을 벗어나면 접근할 수 없다.
		System.out.println(globalNum);
		System.out.println(staticNum);
	}
}
