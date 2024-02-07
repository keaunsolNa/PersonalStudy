package com.greedy.section04.array_sort;

import java.util.Arrays;

public class Application1 {
	public static void main(String[] args) {

		/* 변수의 두 값 변경하기 */
		int num1 = 10;
		int num2 = 20;
		
		/* 변경 전 값 출력 */
		System.out.println("num1: " + num1);
		System.out.println("num12: " + num2);
		
		/* 두 변수의 값을 바꾸기 위해 임시(temporary) 변수 한 개가 더 필요하다. */
		int temp;
		temp = num1;
		num1 = num2;
		num2 = temp;
		
		/* 변경 후 값 출력 */
		
		System.out.println("num1: " + num1);
		System.out.println("num12: " + num2);
		
		/* 배열의 인덱스에 있는 값도 서로 변경할 수 있다. (변수끼리 교체하는 것과 다르지 않다.*/
		int[] arr = {2, 1, 3};
		
		int temp2 =arr[0];
		arr[0] = arr[1];
		arr[1] = temp2;
		
		System.out.println(Arrays.toString(arr));
		
		/* 1. 쌍으로 두번 자리 바꾸기 */
		/* {3, 1, 2} => {1, 2, 3} */
		int[] testArr = {3, 1, 2};
		
		/* {3, 1, 2} -> {1, 3, 2}: 0번째와 1번째 스위칭 */
		int temp3 = testArr[0];
		testArr[0] = testArr[1];
		testArr[1] = temp3;

		/* {3, 1, 2} -> {1, 3, 2}: 1번째와 2번째 스위칭 */
		temp3 = testArr[1];
		testArr[1] = testArr[2];
		testArr[2] = temp3;
		System.out.println(Arrays.toString(testArr));
		
		/*  2. 효율적으로 한번에 바꾸기 */
		int[] testArr2 = {3, 1, 2};
		int temp4 = testArr2[0];
		testArr2[0] = testArr2[1];
		testArr2[1] = testArr2[2];
		testArr2[2] = temp4;
		
		System.out.println(Arrays.toString(testArr2));
	}

}
