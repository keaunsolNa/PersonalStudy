package com.greedy.section01.conditional_statment;
import java.util.Scanner;

public class B_ifElse {
	public void testSimpleIfElseStatement() {
		
		/* 
		 * if문은 조건식의 결과 값이 참이면(true) if{ } 안에 있는 코드를 실행하고,
		 * 조건식의 결과 값이 거짓이면(false) else{ } 안에 있는 코드를 실행한다.
		 * 
		 * 조건을 만족하는지 여부에 따라 둘 중 하나는 무조건 실행해야 하는 경우에 많이 사용한다. (양자택일)
		 */
		
		/*
		 * 정수 한 개를 입력 받아 그 수가 홀수이면 "입력하신 숫자는 홀수입니다." 라고 출력하고,
		 * 홀수가 아니면 "입력하신 숫자는 짝수입니다."라고 출력하는 기능을 작성하자.
		 */
		
		Scanner sc = new Scanner(System.in);
		
		System.out.println("정수를 하나 입력하세요: ");
		int num = sc.nextInt();
		
		if(num % 2 != 0) {		// 홀수일 경우
		System.out.println("입력하신 숫자는 홀수입니다.");
		} else {				// 짝수일 경우
			System.out.println("입력하신 숫자는 짝수입니다.");
		}		
	}

	public void testNestedIfElseStatement() {
		
		/* 중첩된 if-else문 실행 흐름 확인 */
		/* if-else문 안에서 또 다른 조건을 사용하여 if-else문을 사용할 수 있다. */
		
		/*
		 * 숫자를 하나 입력 받아 양수이면 '입력하신 숫자는 양수입니다."를 출력하고,
		 * 음수이면 "입력하신 숫자는 음수입니다."를 출력하자.
		 * (단, 0이면 "0입니다"라고 출력)
		 */
		
		Scanner sc = new Scanner(System.in);
		
		System.out.println("정수를 하나 입력하세요.");
		int num = sc.nextInt();
		
		if(num != 0) {				// 0이 아니라면 -> 양수 또는 음수일 경우
			if(num > 0) {			// 양수라면
				System.out.println("입력하신 숫자는 양수입니다.");
			} else {				// 음수라면
				System.out.println("입력하신 숫자는 음수입니다.");
			}			
		} else {					// 0이라면
			System.out.println("0입니다.");	
		}
		
		/* 양수인지 조건 확인을 먼저 해보자 */
		if(num > 0) {					// 양수일 경우

		} else {						// 0 또는 음수일 경우
			if(num < 0) {				// 홀수일 경우

			} else {					// 0일 경우

			}
		}
	}
	
}


