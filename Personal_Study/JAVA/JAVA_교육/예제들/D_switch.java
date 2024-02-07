package com.greedy.section01.conditional_statment;

import java.util.Scanner;

public class D_switch {
	public void testSimpleSwitchStatement() {
		
		/*
		 * 정수 두 개와 연산 기호 문자를 입력 받아서
		 * 두 숫자의 연산 결과를 출력해 보는 간단한 계산기 만들기
		 */
		Scanner sc =new Scanner(System.in);
		
		System.out.println("첫 번째 정수 입력: ");
		int first = sc.nextInt();
		System.out.println("두 번째 정수 입력: ");
		int second = sc.nextInt();
		System.out.println("연산 기호 입력(+, -, *, /, %): ");
		char op = sc.next().charAt(0);						// nextline을 쓰지 않은 이유는 연산자 하나만 사용하기 때문
		
//		switch(op) {
//			case '+': System.out.println(first + " " + op + " " + second + " = " + (first + second)); break;
//			case '-': System.out.println(first + " " + op + " " + second + " = " + (first - second)); break;
//			case '*': System.out.println(first + " " + op + " " + second + " = " + (first * second)); break;
//			case '/': System.out.println(first + " " + op + " " + second + " = " + (first / second)); break;
//			case '%': System.out.println(first + " " + op + " " + second + " = " + (first % second)); break;
//			default : System.out.println("연산자를 올바르게 입력해 주세요");
//		}
		
			/* 개선된 코드(case별 해당 결과를 한번에 출력하자.(그때그때 출력하지 않고)) */
			int result = 0;					// case별 계산 결과를 나중에 가져와서 출력하기위해 저장 할 변수 선언
			switch(op){
			case '+': 
				result = first + second;
				break;
			case '-':
				result = first - second;
				break;
			case '*': 
				result = first * second;
				break;
			case '/': 
				result = first / second;
				break;
			case '%': 
				result = first % second;
				break;
			default : System.out.println("연산자를 올바르게 입력해 주세요");
		}
			
		if(op == '+' || op =='-' || op == '*' || op == '/' || op == '%') {
			System.out.println(first + " " + op + " " + second + " = " + result);
		}
	
		
		
	}
}
