package com.greedy.section01.literal;

public class Application1 {
	public static void main(String[] args) {
		
		// 한 줄 주석
		
		/* 범
		 * 위
		 * 주
		 * 석*/
		
		/* 범위 주석 */
		
		/* 여러 가지 값의 형태를 출력해 보자 */
		/* 
		 * 1. 숫자 형태의 값
		 * 1-1. 정수 형태의 값
		 * 1-2. 실수 형태의 값
		 * 2. 문자 형태의 값
		 * 3. 문자열 형태의 값
		 * 4. 논리 형태의 값
		 */
		
		/* 1. 숫자 형태의 값 */
		/* 1-1 정수 형태의 값 출력 */
		System.out.println(123);
		System.out.println(-123);
		
		/* 1-2. 실수 형태의 값 출력 */
		System.out.println(1.23);
		
		/* 2. 문자(한 글자) 형태의 값 */
		System.out.println('a');		// 문자 형태의 값은 홀따움표(single-quotation)으로 감싸 주어야 한다.
//		System.out.println('ab');		// 두 개 이상의 문자는 문자열로 취급하기에, 컴파일 에러가 발생한다.
//		System.out.println('');			// 아무 문자도 기록되지 않는 경우에도 컴파일 에러가 발생한다.
		System.out.println('\u0000');	// 아무 문자도 기록하지 않고 싶을 때는 '\u0000' 입력
		System.out.println('1');		// 숫자 값이지만 홀따움표로 감싸져 있는 경우 문자 '1'이라고 판단한다.
		
		/* 3. 문자열 형태의 값 */
		System.out.println("안녕하세요");	// 문자열은 문자 여러 개가 나열 된 형태이며, 쌍따움표(doble-quotation)으로 감싸 주어야 한다.		
		System.out.println("123");		// 쌍따움표로 감싸여 있으면 정수라도 문자열로 확인한다.
		System.out.println("");			// 문자열은 공백도 컴파일 에러 없이 문자열로 취급한다.
		System.out.println("a");		// 한 개의 문자도 쌍따움표로 감싸면 문자열이 된다. (주의! 문자 'a'와는 다르게 취급한다.)
									
		/* 4. 논리 형태긔 값 */
		System.out.println(true);		// ture or false로 논리값을 지정한다.
		System.out.println(false);
		
		/* 5. Extra 연습문*/
		System.out.println(3<1);
		System.out.println(5/2);
		System.out.println("안녕하십니까");
		System.out.println(2.5*2);
		System.out.println(5%2);
		System.out.println(0.5/0.2);
	}

}
