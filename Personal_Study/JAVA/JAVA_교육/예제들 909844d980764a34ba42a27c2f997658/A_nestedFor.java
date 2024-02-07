package com.greedy.section02.looping_statement;

import java.util.Scanner;

public class A_nestedFor {

	public void printGugudanFromTwoToNine() {
		
		/* for문 안에서 for문을 이용할 수 있다. (중첩 반복문) */
		/*
		 * 2 * 1 = 2
		 * 2 * 2 = 4
		 * 2 * 3 = 6
		 * 2 * 4 = 8
		 * 2 * 5 = 10
		 * 2 * 6 = 12
		 * 2 * 7 = 14
		 * 2 * 8 = 16
		 * 2 * 9 = 18
		 * 
		 * 3 * 1 = 3
		 * 3 * 2 = 6
		 * 3 * 3 = 9 
		 * 3 * 4 = 12
		 * 3 * 5 = 15
		 * 3 * 6 = 18
		 * 3 * 7 = 21
		 * 3 * 8 = 24
		 * 3 * 9 = 27
		 * 
		 * ...
		 */
		
		for(int i = 2;i <= 9; i++) {
			System.out.println("======" + i + "단 출력");
			for(int j = 1;j <= 9; j++) {
				System.out.println(i + "*" + j + "=" + (i*j));
			}
			System.out.println("===========");
		}
		
		
		
		
	}

	public void printUpgradeGugudanFromTwoTwoToNine() {
		
		
		for(int i = 2;i <= 9; i++) {
			System.out.println("======" + i + "단 출력");
			printGugudanOf(i);			// 단이 정해지면 1부터 9까지 더하는 기능을 메소드로 분리.
			System.out.println("===========");
		}
	}
	
	public void printGugudanOf(int dan) {
		for(int su = 1;su <= 9; su++) {
			System.out.println(dan + "*" + su + "=" + (dan*su));
		}
	}
	
	public void printStarInputRowTimes() {
		/*
		 * input: 5
		 * *
		 * **
		 * ***
		 * ****
		 * *****
		 */
	
		Scanner sc = new Scanner(System.in);
//		System.out.println("출력할 행 수를 입력하세요: ");
//		int row = sc.nextInt();
//		/* 입력한 행 수만큼 반복 */
//		for(int i = 1; i <= row; i++) {
//			for(int j = 1; j<=i; j++) {
//				System.out.print("*");
//			} 
//			System.out.println("");
//		}
		
		
		int input = sc.nextInt();
		for(int i=1; i<=5; i++) {
			for(int j = 1; j<=(input - i); j++) {
				System.out.println(" ");
			}
			for(int j =1; j <= (i*2-1); j++) {
				System.out.println("*");
			}
			System.out.println();
		}


		

		/*
		 *			*
		 * 			***
		 * 			*****
		 * 			*******
		 */
		
		}
	}
