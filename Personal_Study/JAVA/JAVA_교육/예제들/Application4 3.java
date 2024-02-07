package com.greedy.section01.array;

import java.util.Scanner;

public class Application4 {
	public static void main(String [] args) {
		
		/* 배열을 이용한 예제를 만들어 보자. */
		/* 5명의 자바 점수(정수)를 입력 받아서 합계와 평균을 실수로 구하는 프로그램을 만들어 보자. */
		int[] scores = new int[5];

		Scanner sc = new Scanner(System.in);
		
		/* 반복문을 이용하여 배열 인덱스별로 하나씩 접근해서 학생의 점수 입력 받기 */
		for(int i = 0; i < scores.length; i++) {
			System.out.print((i+1) + "번째 학생의 자바 점수를 입력해 주세요: ");
			scores[i] = sc.nextInt();
		}
		
		/* 합계와 평균을 실수값으로 구한다. */
		double sum = 0.0;
		double avg = 0.0;

		/* 합계는 모든 인덱스의 값을 sum 변수에 누적한 것이다. */
		for(int i = 0; i < scores.length; i++) {
			sum += scores[i];
		}
		
		/* 평균은 합계를 구한 상태에서 배열의 길이(인원수)로 나눈 것이다. */
		avg = sum / scores.length;
		
		System.out.println("합계: " + sum);
		System.out.println("평균: " + avg);
	}
	
}
