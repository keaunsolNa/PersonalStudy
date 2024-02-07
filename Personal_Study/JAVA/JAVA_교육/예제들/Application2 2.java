package com.greedy.section03.constant;

public class Application2 {
	public static void main(String[] args) {
		
		/* 상수의 명명 규칙 */
		/*
		 * 상수의 명명 규칙은 변수의 컴파일 에러를 발생시키는 명명 규칙과 동일하다.
		 * 단, 개발자들끼리의 암묵적인 규칙에서 일부 차이를 보인다.
		 * 
		 * 1. 모든 문자는 영문자 대문자 혹은 숫자만 사용한다.
		 * 2. 단어와 단어 연결은 언더스코어(_)를 사용한다.
		 */
		
		/* 1. 모든 문자는 영문자 대문자 혹은 숫자만 사용한다. */
		final int AGE1 = 20;
		final int AGE2 = 30;
		final int age3 = 40;		// 소문자로 사용은 가능하지만 일반 변수와 구분이 힘들 수 있다.
		
		/* 2. 단어와 단어 연결은 언더스코어(_)를 사용한다. */
		final int MAX_AGE = 60;
		final int MIN_AGE = 20;
		final int MINAGE = 30;		// 역시나 일반 변수와 다르게 대문자들로 구분이 힘들 수 있다.
	}
}



