package com.greedy.section06.finalKeyword;

public class FinalFiledTest {

		/*
		 * final은 변경 불가의 의미를 가진다.
		 * 따라서 초기 인스턴스가 생성되고 나면 기본값 0이 필드에 들어가게 되는데,
		 * 그 초기화 이후 값을 변경할 수 없기 때문에 선언하면서 바로 초기화를 해 주어야 한다.
		 */
	
	/* 컴파일 에러가 발생함 */
//	private final int NONSTATICNUM;

	/* 이를 해결할 수 있는 방법은 2가지가 있다. */
	/* 1. 선언과 동시에 초기화 한다. */
//	private final int NONSTATICNUM = 1;
	
	/* 2. 생성자를 이용해서 초기화 할 수도 있다. */
//	private final int NONSTATICNUM;
	
//	public FinalFiledTest() {
//			NONSTATICNUM = 1;
//	} 
	
	/* static field에 final 사용 */
	/*
	 * static일 지라도 자바에서 지정한 기본 값이 초기에 대입되기 때문에
	 * final 키워드 사용 시에는 초기화를 하지 않으면 에러가 발생한다.
	 * static과 final의 위치는 서로 상관은 없고 접근제한자와 자료형 사이이기만 하면 된다.	
	 */

//	private final static int STATIC_NUM;			// 에러남
	private static final int STATIC_NUM = 1;		// 선언과 동시에 초기화 하자.
//	private final static int STATIC_NUM = 1;		// 선언과 동시에 초기화 하자.

	
	/* static일 경우는 생성자를 이용한 초기화가 의미가 없다. */
	/*
	 * 생성자를 이용한 초기화는 불가능하다.
	 * 생성자는 인스턴스가 생성되는 시점에 호출이 되기 때문에 그 전에는 초기화를 할 수가 없다.
	 * 하지만 static은 프로그램이 start될 때 처음부터 메모리의 static영역에 할당되기 때문에
	 * 초기화가 되지 않은 상태로 생성되므로 기본값으로 초기화 된 후 값을 변경할 수 없기 때문에
	 * 에러가 발생한다.
	 */
//		private final static int STATIC_NUM;
	
//		public FinalFiledTest() {					
//		STATIC_NUM = 1;							
//	}

}
