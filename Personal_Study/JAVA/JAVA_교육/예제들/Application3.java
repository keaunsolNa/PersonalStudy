package com.greedy.section01.literal;

public class Application3 {
	public static void main(String[] args) {
		
		/* 문자열 합치기(이어붙이기) 테스트 */
		System.out.println("======== 두 항의 문자열 합치기 ========");
		System.out.println(9 + 9);			// 18
		System.out.println("9" + 9);		// "99" ("9" + "9")
		System.out.println(9 + "9");		// "99" ("9" + "9")
		System.out.println("9" + "9");		// "99"
		
		System.out.println("======== 세 항의 문자열 합치기 ========");
		System.out.println(9 + 9 + "9");	// "189" ("18" + "9")
		System.out.println(9 + "9" + 9);	// "999" ("9" + "99")
		System.out.println("9" + 9 + 9);	// "999" ("9" + "9" + "9")
		System.out.println("9" + (9 + 9));	// "918" ("9" + "18")

		/* 문자열 합치기 응용 */
		System.out.println("======== 10과 20의 사칙연산과 mod(%) 결과");
		System.out.println("10과 20의 합: " + (10 + 20));
		System.out.println("10 빼기 20: " + (10 - 20));
		System.out.println("10과 20의 곱: " + (10 * 20));
		System.out.println("10과 20의 나누기: " + (10 / 20));
		System.out.println("10과 20의 나누고 나머지: " + (10 % 20));
		
		
	}
}
