package com.greedy.section03.array_copy;

import java.util.Arrays;

public class Application3 {
	public static void main(String[] args) {

		/*
		 * 깊은 복사는 원본과 사본 중 둘 중 한가지 값을 변경해도 다른 하나에 영향을 주지 않는다.
		 * 같은 값을 지니고 있지만 다른 배열이기 때문이다.
		 * 
		 * 이러한 깊은 복사의 특성을 이용하는 자바 구문을 살펴보자.
		 */
		
		int[] arr1 = {1, 2, 3, 4, 5};
		int[] arr2 = arr1.clone();
		
		/* 각 배열의 인덱스에 10씩 누적 증가 시켜보자. */
		for(int i = 0; i < arr1.length; i++) {
			arr1[i] += 10;
		}
		
		System.out.println(Arrays.toString(arr1));
		System.out.println("10 누적 전 arr2: " +  Arrays.toString(arr2));
		
		/* for-each문 (jdk 1.5)으로 진행해 보자. */
		/*
		 * for-each문(향상된 for문)은 배열의 처음부터 끝까지 돌아가면서 배열 안에 있는 값을
		 * 하나의 변수에 저장해서 편하게 사용하기 위해 만들어졌다. 
		 */
		for(int i : arr1) {
			System.out.print(i + " ");
		}

		/* 위 for문에서의 i는 배열 안의 값이 된다. */
		/* 단, 위의 for-each문의 경우 배열의 처음부터 끝까지 반드시 실행된다. */
		System.out.println();
		for(int i = 0; i < arr1.length; i++) {
			System.out.print(arr1[i] + " ");
		}
		
		/* 
		 * 위 for문과 아래 for문은 동일한 기능을 한다.
		 * 단, 여기서의 i는 배열의 인덱스가 된다. 
		 * 대신 for문과 같은 경우, 인덱스의 범위를 설정함으로서 배열의 출력 범위 조절이 가능하다.
		 */
	
	}
}
