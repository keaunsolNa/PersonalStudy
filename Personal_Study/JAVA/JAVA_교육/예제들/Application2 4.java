package com.greedy.section05.logical_operator;

public class Application2 {

	public static void main(String[] args) {
		
		/* 논리 연산자의 우선순위와 활용 */
		/*
		 * 논리 and 연산자와 논리 or 연산자의 우선순위
		 * && : 11순위
		 * || : 12순위
		 * 논리 and 연산자 우선순위가 논리 or 연산자보다 높다.
		 */
		
		/* 1. 1부터 100사이의 값인지 확인 */
		/*
		 *  1<= 변수 <= 100 이렇게는 사용할 수 없다.
		 *  변수 >= 1 && 변수 <== 100 이렇게 사용해야 한다.
		 */

		int num1 = 55;
		System.out.println("1부터 100 사이의 값인지 확인: " + ((num1 >= 1) && (num1 <= 100)));
		
		int num2 = 166;
		
		System.out.println("1부터 100 사이의 값인지 확인: " + ((num2 >= 1) && (num2 <= 100)));

		/* 2. 영어 대문자인지 확인 */
		/* 영어 대문자냐? */
		char ch1 = 'G';
		System.out.println("영어 대문자인지 확인: " + (ch1 >= 'A' && ch1 <= 'Z'));
		System.out.println("영어 대문자인지 확인: " + (ch1 >= 65 && ch1 <= 90));
		
		char ch2 = 'g';
		System.out.println("영어 대문자인지 확인: " + ((ch2 >= 'A') && (ch2 <= 'Z')));
		System.out.println("영어 대문자인지 확인: " + ((ch2 >= 65) && (ch2 <= 90)));

		/* 영어냐(대소문자 모두 포함)? */
		char ch3 = 'c';
		System.out.println("영어인지 확인: " + (((ch3 >= 'A') && (ch3 <= 'z')) || ((ch3 >= 'a') && (ch3 <= 'z'))));
		
	}

}
