package com.greedy.section02.dimensional.array;

import java.util.Arrays;

public class Application4 {

	public static void main(String[] args) {

		/*
		 * 2차원 배열도 크기 할당과 동시에 JVM 기본값 외의 값으로 초기화 하고 싶은 경우
		 * 리터럴을 이용할 수 있다.
		 */
		
		/* 정변 배열*/
		int[][] iArr = {{1, 2, 3, 4, 5}, {10, 7, 6, 8, 9},{11, 12, 13, 14, 15}};
		
		/* 출력 */
		for(int i = 0; i <iArr.length; i++) {
			System.out.println(Arrays.toString(iArr[i]));
		}

		/* 가변 배열 */
		int[][] iArr2 = {{1, 2, 3}, {10, 7}, {11, 12, 13, 14, 15}};
	
		/* 출력 */
		for(int i = 0; i < iArr2.length; i++) {
			System.out.println(Arrays.toString(iArr2[i]));
		}
	
		/* 미리 할당 된 1차원 배열을 이용하는 방식 */
		int[] arr = {1, 2, 3};
		int[] arr2 = {4, 5, 6};
		
		int[][] iArr3 = {arr, arr2};
		
		/* 출력 */
		for(int i = 0; i < iArr3.length; i++) {
			System.out.println(Arrays.toString(iArr3[i]));
		}
		
	}
}
