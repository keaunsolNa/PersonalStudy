package com.greedy.section01.method;

public class Application7 {

	public static void main(String[] args) {
		
		/* static 메소드 호출 */
		/*
		 * 우리가 지금 작성하고 있는 메소드를 보면 public과 void 사이에 static이라고 하는 키워드가 있다.
		 * static 키워드에 대해서는 뒤에서 다시 다룰 예정이지만
		 * static 메소드를 호출하는 방법부터 먼저 학습하자.
		 * static이 있는 메소드이건 non-static 메소드이건 메소드의 동작 흐름은 동일하다.
		 */
		System.out.println("10과 20의 합: " + Application7.sumTwoNumbers(10, 20));		// 10과 20을 전달인자로써 넘겨주고 메소드 호출 후 반환값을 출력한다.
		System.out.println("10과 20의 합: " + sumTwoNumbers(10, 20));						// 같은 클래스에 있으므로 클래스명을 생략해도 된다.
	}

	public static int sumTwoNumbers(int first, int second) {
		
//		return 0;		//반환값이 있을 경우 반드시 return을 명시적으로 써 줘야 한다.

//		return first + second;
		
		int result = first + second;
		return result;
		
	}
}
