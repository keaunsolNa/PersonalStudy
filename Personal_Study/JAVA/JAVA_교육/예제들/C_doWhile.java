package com.greedy.section02.looping_statement;

import java.util.Scanner;

public class C_doWhile {

	public void testSimpleDoWhileStatement() {
		
		do {
			System.out.println("최초 실행...");
		} while(false);
	}
	
	public void testDoWhileExample1() {
		
		/*
		 * 키보드로 문자열 입력 받아 반복적으로 출력
		 * 단, exit가 입력이 되면 반복문을 종료한다.
		 */
//		
//		do {
//			
//		}while(만약 exit가 입력이 되지 않았다면);
//	
		/*
		 * equals(): 문자열이 같은지 비교할 때 사용
		 * 문자열이 같으면 true를 반환하고 그렇지 않으면 false를 반환한다.
		 * 
		 */
//		Scanner sc = new Scanner(System.in);
//		String str = "";
//		do {
//			System.out.println("문자열을 입력하세요: ");
//			str = sc.nextLine();
//		}while(!str.equals("exit"));
//		
		Scanner sc = new Scanner(System.in);
		String str = "";
		while(!str.equals("exit")) {
			System.out.println("문자열을 입력하세요: ");
			str = sc.nextLine();

		}
		
		
	}
	
}
