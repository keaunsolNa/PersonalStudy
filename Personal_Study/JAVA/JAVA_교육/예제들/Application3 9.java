package com.greedy.section01.array;

public class Application3 {
	
	public static void main(String [] args) {
	
			/*
			 * 기본적으로 배열을 선언하고 할당하게 되면
			 * 배열의 각 인덱스에는 자바에서 지정한 기본값으로 초기화가 된 상태이다.
			 * heap 영역은 값이 없는 빈 공간이 존재할 수 없다.
			 */
		double[] dArr = new double[5];
		
		/* double의 기본값인 0.0으로 채워져 있음을 확인 */
		System.out.println(dArr[0]);
		System.out.println(dArr[1]);
		System.out.println(dArr[2]);
		System.out.println(dArr[3]);
		System.out.println(dArr[4]);
	
		/* 반복문을 통해서 확인 */
		for(int i = 0; i < dArr.length; i++) {
			System.out.println("dArr[" + i +"]의 값: " + dArr[i]);
		}
		
		/* 
		 * 자바에서 지정한 기본값 외의 값으로 초기화를 하고 싶은 경우 블우({ }) 을 사용한다.
		 * 블럭을 사용하는 경우에는 new를 사용하지 않아도 되며, 값의 갯수만큼 자동으로 크기가
		 * 설정된다.
		 */
		int[] iArr = {11, 22, 33, 44, 55};
		int[] iArr2 = new int[] {22, 33, 44, 55, 66};
		
		System.out.println("iArr의 길이: " + iArr.length);
		System.out.println("iArr2의 길이: " + iArr2.length);
	
		/* 초기화 된 값을 확인 */
		
		for(int i = 0; i < iArr.length; i++) {
			System.out.println("iArr[" + i +"]의 값: " + iArr[i]);
		}
		
		for(int i = 0; i < iArr2.length; i++) {
			System.out.println("iArr2[" + i +"]의 값: " + iArr2[i]);
		}
		
		
		
	}
}
