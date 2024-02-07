package com.greedy.section01.array;

public class Application1 {
	public static void main(String[] args) {

		/*
		 * 배열이란?
		 * 동일한 자료형의 묶음 (연속된 메모리 공간에 값을 저장하고 사용하기 위한 용도) 이다.
		 * 배열은 heap 영역에 new 연산자를 이용하여 크기를 할당한다.
		 */
		
		/*
		 * 배열을 사용하는 이유
		 * 만약 배열을 사용하지 않는다면 변수를 여러 개 사용해야 한다.
		 * 1. 연속된 메모리 공간으로 관리할 수 없다. (모든 변수의 이름을 사용자가 관리해야 한다.)
		 * 2. 반복문을 이용한 연속 처리가 불가능하다.(하나의 이름이 아니므로)
		 */
		
		/*
		 * 변수 5개의 값을 저장한다.
		 * 이 때 사용자(개발자)는 변수의 이름을 모두 알아야 한다.
		 */
		int num1 = 10;
		int num2 = 20;
		int num3 = 30;
		int num4 = 40;
		int num5 = 50;
		
		/* 변수긔 값을 누적해서 저장하기 위한 용도의 변수 sum */
		int sum = 0;
		
		/* 반복문을 사용하지 못 하고 일일히 더해 줘야 한다. */
		sum += num1;
		sum += num2;
		sum += num3;
		sum += num4;
		sum += num5;
				
		System.out.println("합계: " +  sum);
		
		/* 배열을 이요해 보자. */
		/* 배열의 선언 및 할당 */
		
		int[] arr = new int[5];		// 처음 배열을 할당할 때 []안에 넣은 값은 저장할 크기(index가 아니다)이며 반드시 지정해야 한다.
		
		/*
		 * 하나의 이름으로 관리되는 연속된 메모리 공간이고, 공간마다 찾아갈 수 있는 
		 * 번호(인덱스)를 이용해 접근한다.
		 */
		arr[0] = 10;
		arr[1] = 20;
		arr[2] = 30;
		arr[3] = 40;
		arr[4] = 50;

		/* 배열에 저장할 값이 규칙성이 있을 때는 반복문을 활용해서 코드를 줄일 수 있다. */
		for(int i = 0; i < arr.length; i++) {
			arr[i] = (i+1)*10;
		}
		/* 배열에 들어있는 값을 반복문을 통해 확인 */
		for(int i = 0; i < arr.length; i++) {
			System.out.println(arr[i]);
		}

	
		
	
	}
}