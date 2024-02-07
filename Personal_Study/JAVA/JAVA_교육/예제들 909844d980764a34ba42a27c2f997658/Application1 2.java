package com.greedy.section03.constant;

public class Application1 {
	public static void main(String[] args) {
		
		/*
		 * 상수란?
		 * 변수가 메모리에 변경될 값을 저장하기 위한 공간을 나타낸다면,
		 * 상수는 이와 반대되는 개념이다.
		 * 변하지 않는 값을(항상 고정된 값을) 저장해 두기 위한 메모리 상의 공간을 상수라고 한다.
		 * 
		 * 상수의 사용 목적
		 * 변경되지 않는 고정된 값을 저장 할 목적으로 사용되며
		 * 초기화 이후 다른 값 대입 시 컴파일 에러를 발생시켜 값이 수정되지 못하도록 한다.
		 */
		
		/* 1. 상수 선언 */
		/* 상수 선언 시 자료형 앞에 final 키워드를 붙인다. */
		final int AGE;
		
		/* 2. 초기화 */
		AGE = 20;
//		AGE = 30;		// 한 번 초기화 된 이후 값을 또 다시 대입하는 것은 불가능하다.
		
		/* 3. 필요한 위치에서 상수를 호출하여 사용한다. */
		System.out.println("AGE의 값: " + AGE);
		
		int varAge = AGE;
		System.out.println(varAge);
	}
}















