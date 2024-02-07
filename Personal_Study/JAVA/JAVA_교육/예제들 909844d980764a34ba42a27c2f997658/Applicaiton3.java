package com.greedy.section03.api.scanner;

import java.util.Scanner;

public class Applicaiton3 {
	public static void main(String[] args) {
		
		/* 연습하기 */
		
		/* 스캐너 객체 생성하기 */
		Scanner sc = new Scanner(System.in);
		
		System.out.print("숫자를 입력해 주세요: ");
		int num = sc.nextInt();
		System.out.println("num: " + num);
		
		System.out.print("공백을 포함한 문자열을 하나 입력해 주세요: ");
		String str = sc.next();		// 첫 공백 이전까지만 인지
		System.out.println("첫 공백 이전: " + str);
		
		System.out.println("첫 공백 이후: " + sc.next());
		
		sc.nextLine();				// 엔터 제거용
		System.out.println("한번 더 문자열 입력받기: " + sc.nextLine());
				
	}

}
