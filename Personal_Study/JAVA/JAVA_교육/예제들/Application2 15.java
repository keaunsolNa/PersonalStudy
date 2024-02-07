package com.greedy.section02.dimensional.array;

import java.lang.reflect.Array;
import java.util.Arrays;

public class Application2 {

	public static void main(String[] args) {

		/* 2차원 정변 배열을 선언 및 할당하고 차례로 값을 대입해서 출력해 보자. */
		
		/* 
		 * 1. 배열의 선언 및 할당
		 * 정변 배열의 경우 각 인덱스별 배열을 따로 할당할 수도 있지만
		 * 선언과 동시에 모든 배열을 할당할 수도 있다.
		 * 자료형[][] 변수명 = new 자료형[할당 할 배열의 갯수][각각 할당 할 1차원 배열의 길이]
		 */
		
		int[][] iArr = new int[3][5];
		
		/* 
		 * 길이 5인 1차원 배열 3개를 heap에 할당하고 그 주소를 묶어 관리하는 배열의 주소를
		 * stack의 iArr에 저장한다.
		 */
		
		/* 2. 각 배열의 인덱스에 접근해서 값 대입 후 출력 */
		/* 값 대입 */
		iArr[0][0] = 1;
		iArr[0][1] = 2;
		iArr[0][2] = 3;
		iArr[0][3] = 4;
		iArr[0][4] = 5;

		iArr[1][0] = 6;
		iArr[1][1] = 7;
		iArr[1][2] = 8;
		iArr[1][3] = 9;
		iArr[1][4] = 10;

		iArr[2][0] = 11;
		iArr[2][1] = 12;
		iArr[2][2] = 13;
		iArr[2][3] = 14;
		iArr[2][4] = 15;
		
		/* 값 출력 */
		System.out.println(iArr[0][0] + " ");
		System.out.println(iArr[0][1] + " ");
		System.out.println(iArr[0][2] + " ");
		System.out.println(iArr[0][3] + " ");
		System.out.println(iArr[0][4] + " ");
		System.out.println();
		
		System.out.println(iArr[1][0] + " ");
		System.out.println(iArr[1][1] + " ");
		System.out.println(iArr[1][2] + " ");
		System.out.println(iArr[1][3] + " ");
		System.out.println(iArr[1][4] + " ");
		System.out.println();

		System.out.println(iArr[2][0] + " ");
		System.out.println(iArr[2][1] + " ");
		System.out.println(iArr[2][2] + " ");
		System.out.println(iArr[2][3] + " ");
		System.out.println(iArr[2][4] + " ");
		System.out.println();
		
		/*
		 * 값을 대입하고 출력한 것에 규칙을 살펴보고,
		 * 그 규칙대로 반복문을 이용해서 값을 대입 후 출력하는 구문으로 바꿔보자.
		 */
		
		/* 값 대입 */
		int value = 1;
		
		for(int i = 0; i < iArr.length; i++) {
			for(int j=0; j < iArr[i].length; j++) {
				iArr[i][j] = value++;
				
			}
		}

		
		for(int i = 0; i < iArr.length; i++) {
			for(int j=0; j < iArr[i].length; j++) {
				System.out.print(iArr[i][j] + " ");
			}
			System.out.println();
		}
		
		/* Arrays에서 제공하는 toString()을 통해서 출력을 쉽게 하자. (1차원 배열만 제공) */
		System.out.println(Arrays.toString(iArr[0]));
		
		for(int i = 0; i <iArr.length; i++) {
			System.out.println(Arrays.toString(iArr[i]));		
		}
	}
}