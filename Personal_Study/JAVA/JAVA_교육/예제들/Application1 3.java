package com.greedy.section04.overflow;

public class Application1 {
	public static void main(String[] args) {
		
		/* 데이터 오버플로우 */
		/*
		 * 자료형 별 값의 최대 범위를 벗어나는 경우
		 * 발생한 carry를 버림 처리하고 sign bit(MSB)를 반전시켜 최소값으로 순환시킴
		 */
		byte num1 = 126;
		
//		num1 = (byte)(num1 + 1);
		num1++;		// num1에 담긴 값 +1
		System.out.println("num1: " + num1);	// 127: byte의 최대 저장 범위(양수 범위에서)
		
//		num1 = (byte)(num1 + 1);
		num1++;
		System.out.println("num1: " + num1);	// -128
		
		/* 데이터 언더플로우 */
		/* 오버플로우와 반대 개념으로 최소 범위보다 작은 수를 발생 시키는 경우 발생하는 현상이다. */
		byte num2 = -127;
		
		num2--;		// num2에 담긴 값 -1
		System.out.println("num2: " + num2);	// -128
		
		num2--;
		System.out.println("num2: " + num2);	// 127
		
		num2--;
		System.out.println("num2: " + num2);	// 126
		
		/*
		 * 컴파일 에러나 런타임 에러가 발생하지 않고 원치 않는 값이 나온다.
		 * 따라서, 우리는 적절한 자료형을 판단하여 변수를 사용해야 한다.
		 */
		
		int firstNum = 1000000;		// 100만
		int secondNum = 700000;		// 70만
		
		int mul = firstNum * secondNum;		// 7000억이 나와야 함
		
		System.out.println("firstNum * secondNum = " + mul);
		
		/* 해결 방법 */
		/* 오버플로우를 예측하고 더 큰 자료형으로 결과 값을 받아서 처리한다. */
		long longMul = firstNum * (long)secondNum;		// int와 int연산으로만 안되게 하자.
		
		System.out.println("longMul: " + longMul);		// 정상적으로 7000억이 나온걸 확인할 수 있다.
	}
}








