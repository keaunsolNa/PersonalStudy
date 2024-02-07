package com.greedy.section05.logical_operator;

public class Application01 {
	public static void main(String[] args) {
		
		/* 논리 연산자 */
		/* 논리값(true or false)를 취급하는 연산자이다. */
		
		/*
		 *   논리 연산자의 종류
		 *   1. 논리 연결 연산자 : 두 개의 피 연산자를 가지는 이항 연산자이며, 연산자의 결합 방법은 왼쪽에서 오른 쪽이다.
		 *   	1-1. &&(논리 AND) 연산자 : 두 개의 논리식 모두 true일 경우 true를 반환, 둘 중 한개라도 false인 경우 false를 반환한다.
		 *   	1-2. ||(논리 OR) 연산자 : 두 개의 논리식 중 하나라도 true일 경우 true를 반환, 둘 모두 거짓일 경우에만 false를 반환한다.
		 *   
		 *   2. 논리 부정 연산자 : 피연산자가 하나인 단항 연산자로, 피연산자의 결합 방법은 왼쪽에서 오른쪽이다.
		 *   	1-1. !(논리 NOT) 연산자 : 논리식의 결과가 true면 false를, false면 true를 반환한다.
		 */
		
		/* 1. 논리 연결 연산자 결과값 확인 */
		System.out.println("true와 true의 논리 and 연산: " + (true && true));		// t
		System.out.println("true와 false의 논리 and 연산: " + (true && false));		// f
		System.out.println("false와 true의 논리 and 연산: " + (false && true));		// f
		System.out.println("false와 false의 논리 and 연산: " + (false && false));	// f
		
		System.out.println("true와 true의 논리 or 연산: " + (true || true));		// t
		System.out.println("true와 false의 논리 or 연산: " + (true || false));		// t
		System.out.println("false와 true의 논리 or 연산: " + (false || true));		// t
		System.out.println("false와 false의 논리 or 연산: " + (false || false));	// f
		
		/* 2. 논리 부정 연산자 결과값 확인. */ 
		System.out.println("true의 논리 not 연산: " + (!true));							// f
		System.out.println("false의 논리 not 연산: " + (!false));						// t

	}

}
