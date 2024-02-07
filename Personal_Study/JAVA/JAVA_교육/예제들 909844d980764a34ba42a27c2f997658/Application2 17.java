package com.greedy.section04.array_sort;

import java.util.Arrays;

public class Application2 {

	public static void main(String[] args) {

		/* 순차 정렬 */
		/*
		 * 순차 정렬이란 정렬 알고리즘에서 가장 간단하고 기본이 되는 알고리즘으로
		 * 배열의 처음과 끝을 탐색하면서 순차대로 정렬하는 가장 기초적인 정렬 알고리즘이다.
		 */
		
		/* 초기 배열 선언 및 초기화 */
		int[] iArr = {5, 4, 6, 1, 3};
		
		for(int i =1; i < iArr.length; i++) {
			for(int j = 0; j < i ; j++) {
				System.out.println("i의 값은: " + i + ", j의 값은: " + j + ",");
				if(iArr[j] > iArr[i]) {
					int temp = 0;
					temp = iArr[i];
					iArr[i] = iArr[j];
					iArr[j] = temp;
				}
				System.out.println(Arrays.toString(iArr));
			}
		}
	
	
		System.out.println(Arrays.toString(iArr));
	}
}
