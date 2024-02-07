package com.greedy.section03.api.scanner;

import java.util.Scanner;

public class Application2 {
	public static void main(String[] args) {
		
		/* Scanner의 nextLine()과 next() */
		/*
		 * nextLine(): 공백을 포함한 한 줄 입력을 위한 개행문자까지 문자열로 반환한다. (공백문자 포함)
		 * next() : 공백문자나 개행문자 전까지 읽어서 문자열로 반환한다. (공백문자 미포함)
		 */
		
		/* 1. Scanner 객체 생성 */
		Scanner sc = new Scanner(System.in);
		
		/* 2. 문자열 입력 */
		/* 2-1. nextLine() */
//		System.out.print("인사말을 입력해 주세요: ");
//		String greeting1 = sc.nextLine();
//		
//		System.out.println(greeting1);
		
		/* 2-2. next() */
		System.out.print("인사말을 입력해 주세요: ");
		String greeting2 = sc.next();
		String greeting3 = sc.next();
		String buffer = sc.nextLine();
		
		System.out.println("1줄~" + greeting2);
		System.out.println("2줄~" +greeting3);
		System.out.println("3줄~" +buffer);
		
	
		
	}

}
