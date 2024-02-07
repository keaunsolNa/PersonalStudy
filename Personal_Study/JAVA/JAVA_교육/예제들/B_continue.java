package com.greedy.section03.branching_statement;

public class B_continue {

	/*
	 *  continue문은 반복문 내에서 사용한다.
	 *  해당 반복문의 반복 회차를 중간에 멈추고 다시 증강식으로 넘어가게 해준다. (for문의 경우)
	 *  일반적으로 if(조건식) {continue;}처럼 사용된다.
	 *  보통 반복문 내에서 특정 조건에 대한 예외를 처리하고자 할 때 자주 사용된다.
	 */
	public void testSimpleContunueStatement() {
		
		/* 1부터 100 사이의 4의 배수이면서 5의 배수인 값 출력(4와 5의 공배수 출력) */
		for(int i = 1; i <= 100; i++) {
			if(!((i % 4 == 0) && (i % 5 ==0))) {
				continue;
			}
			System.out.println(i);
		}
	}

	public void testSimpleContinueStatement2() {
		/*
		 * 구구단 2~9단까지 출력
		 * 단, 각 단의 곱하는 수가 짝수인 경우 출력을 생략한다.
		 * 
		 * ex)
		 * 2 * 1 = 2
		 * 2 * 3 = 6
		 * 2 * 5 = 10
		 * ---
		 * 9 * 9 = 81
		 * (for문을 이용한 중첩반복문과 continue를 활용해서 풀 것!!)
		 */
		
		for(int dan=2;dan<=9;dan++) {
			for(int su=1;su<=9;su++) {
				if(su%2==0) continue;
				System.out.println(dan + "*" + su + "=" + (dan*su));
			}
			System.out.println();
		}

	}
}
