package com.greedy.section03.array_copy;

import java.util.Arrays;

public class Application1 {
	public static void main(String[] args) {

		/* 배열의 복사  */
		/*
		 * 배열의 복사에는 크게 두 가지 종류가 있다.
		 * 1. 얕은 복사(shallow copy) : stack의 주소 값만 복사
		 * 2. 깊은 복사(deep copy) : heap의 배열에 저장된 값을 복사(원본 -> 사본)
		 */
		
		/* 얕은 복사 확인을 위한 원본 배열 생성 */
		int[] originArr = {1, 2, 3, 4, 5};
		int[] copyArr = originArr;				// 얕은 복사

		/* hashCode를 출력해서 두 개의 레퍼런스 변수가 동일한 주소를 가지고 있는지 확인하자. */
		System.out.println(originArr.hashCode());
		System.out.println(copyArr.hashCode());

		
		System.out.println(Arrays.toString(originArr));
		System.out.println(Arrays.toString(copyArr));

		copyArr[0] = 99;
		System.out.println(Arrays.toString(originArr));		// originArr도 같은 것을 참조하므로 값이 바뀐 것을 확인
		System.out.println(Arrays.toString(copyArr));
	}
}
