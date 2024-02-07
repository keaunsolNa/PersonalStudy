package com.greedy.section05.logical_operator;

public class Application3 {

	public static void main(String[] args) {
		
		/* AND 연산과 OR 연산의 특징 */
		/*
		 * 논리식 && 논리식 : 앞의 결과가 false이면 뒤를 실행하지 않는다.
		 * 논리식 || 논리식 : 앞의 결과가 true이면 뒤를 실행하지 않는다.
		 */
		
		/* 1. 논리식 && 논리식 */
		int num1 = 10;
		
		int result1 = (false && ++num1 > 0) ? num1 : num1;
		
		System.out.println("&& 이후 실행 확인: " + result1);
		
		/* 2. 논리식 || 논리식 */
		int num2 = 10;
		
		int result2 = (true || ++num2 > 0) ? num2 : num2;
		
		System.out.println("|| 이후 실행 확인: " + result2);

	}

}
