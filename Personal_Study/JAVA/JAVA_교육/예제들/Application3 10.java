package com.greedy.section02.dimensional.array;

import java.util.Arrays;

public class Application3 {
	public static void main(String[] args) {

		/* 2차원 가변배열을 선언 및 할당하고 차례로 값을 대입해서 출력해 보자. */
		
		/* 1. 배열의 선언 및 할당 */
		int[][] iArr = new int[3][];
		
		iArr[0] = new int[3];
//		iArr[0] = new char[3];				// 자료형이 다른 배열은 하나로 묶어서 관리할 수 없다.
		iArr[1] = new int[2];
		int[] arr = new int[5];
		iArr[2] = arr;						// 미리 할당해 둔 1차원 배열을 활용할 수도 있다.
		
		/* 2. 각 1차원 배열의 인덱스마다 접근해서 값 대입 후 출력 */
		iArr[0][0] = 1;
		iArr[0][1] = 2;
		iArr[0][2] = 3;
//		iArr[0][3] = 4;						//존재하지 않는 인덱스에 접근하는 경우 실행 시에 ArrayIndexOutOfBoundsException이 발생
		
		iArr[1][0] = 4;
		iArr[1][1] = 5;
		
		iArr[2][0] = 6;
		iArr[2][1] = 7;
		iArr[2][2] = 8;
		iArr[2][3] = 9;
		iArr[2][4] = 10;
		
		/* 값 출력 */
		System.out.print(iArr[0][0] + " ");
		System.out.print(iArr[0][1] + " ");
		System.out.print(iArr[0][2] + " ");
		System.out.println();
		
		System.out.print(iArr[1][0] + " ");
		System.out.print(iArr[1][1] + " ");
		System.out.println();
		
		System.out.print(iArr[2][0] + " ");
		System.out.print(iArr[2][1] + " ");
		System.out.print(iArr[2][2] + " ");
		System.out.print(iArr[2][3] + " ");
		System.out.print(iArr[2][4] + " ");
		System.out.println();
		
		/* 반복문을 이용한 값 대입 출력*/
		
		int value = 0;
		
		for(int i = 0; i < iArr.length; i++) {
			for(int j = 0; j < iArr[i].length; j++) {
				iArr[i][j] = ++value;
			}
		}
		
		for(int i = 0; i < iArr.length; i++) {
			System.out.println(Arrays.toString(iArr[i]));
			}

	}
}

