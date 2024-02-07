package com.greedy.section02.looping_statement;

import java.util.Scanner;

public class A_for {

	public void testSimpleForStatement() {
		
		/* 1부터 10까지 1씩 증가하면서 (10번) 반복하는 i값을 출력해 보는 기본 반복문 */
		/*
		 *  동안(1부터 ; 10까지 ; 1씩증가) {
		 *  	i값 출력
		 *  } 
		 */
		for(int i = 1; i <= 10; i++) {
			System.out.println(i + "출력");
		}
		
		/* 10부터 1까지 반복하며 출력하는 반복문 만들기 */
		
		for(int i = 10; i >= 1; i--) {
			System.out.println(i + "출력");
		}
		
		/* 반복문의 변수를 사용하지 않을 경우 */
		int num = 0;
		for(int i = 2; i < 12; i++) {		// 10바퀴 도는 반복문 (2 ~ 11까지 1씩 증가하며 변화)
			System.out.println(++num + "출력");			
		}
	}
	
	public void testForExample1() {
		
		/* 10명의 학생 이름을 입력 받아 이름을 출력해 보자. */
		Scanner sc = new Scanner(System.in);

		//System.out.println("1번째 학생의 이름을 입력해 주세요: ");
		//String student1 = sc.nextLine();
		//System.out.println("1번째 학생의 이름은 " + student1 + "입니다.");
		//
		//System.out.println("2번째 학생의 이름을 입력해 주세요: ");
		//String student2 = sc.nextLine();
		//System.out.println("2번째 학생의 이름은 " + student2 + "입니다.");
		//
		//System.out.println("3번째 학생의 이름을 입력해 주세요: ");
		//String student3 = sc.nextLine();
		//System.out.println("3번째 학생의 이름은 " + student3 + "입니다.");
		//
		//System.out.println("4번째 학생의 이름을 입력해 주세요: ");
		//String student4 = sc.nextLine();
		//System.out.println("4번째 학생의 이름은 " + student4 + "입니다.");
		//
		//System.out.println("5번째 학생의 이름을 입력해 주세요: ");
		//String student5 = sc.nextLine();
		//System.out.println("5번째 학생의 이름은 " + student5 + "입니다.");
		//
		//System.out.println("6번째 학생의 이름을 입력해 주세요: ");
		//String student6 = sc.nextLine();
		//System.out.println("6번째 학생의 이름은 " + student6 + "입니다.");
		//
		//System.out.println("7번째 학생의 이름을 입력해 주세요: ");
		//String student7 = sc.nextLine();
		//System.out.println("7번째 학생의 이름은 " + student7 + "입니다.");
		//
		//System.out.println("8번째 학생의 이름을 입력해 주세요: ");
		//String student8 = sc.nextLine();
		//System.out.println("8번째 학생의 이름은 " + student8 + "입니다.");
		//
		//System.out.println("9번째 학생의 이름을 입력해 주세요: ");
		//String student9 = sc.nextLine();
		//System.out.println("9번째 학생의 이름은 " + student9 + "입니다.");
		//
		//System.out.println("10번째 학생의 이름을 입력해 주세요: ");
		//String student10 = sc.nextLine();
		//System.out.println("10번째 학생의 이름은 " + student10 + "입니다.");
		//
		for(int i = 0; i<=10; i++) {
			System.out.println(i + "번째 학생의 이름을 입력해 주세요: ");
			String student = sc.nextLine();
			System.out.println(i + "번째 학생의 이름은 " + student + "입니다.");
		}
		
		/* 
		 * 반복문이 더 좋은 이유
		 * 1. 반복의 횟수가 늘어갈수록 for문을 활용한 코드가 작성 시간이 적다. (시간절약)
		 * 2. 반복되는 부분을 보기 좋고 간결한 코드로 작성할 수 있다. (가독성)
		 * 3. 학생의 이름 뿐 아니라 성적도 입력하는 기능이 추가 된다면? (유지보수성)
		 */
	}
	
	public void testForExample2() {
		
		/* 문장 속에서 규칙 찾기 */
		
		/* 
		 * 1~10까지의 합계를 구해보자.
		 * 
		 * 1부터 10까지 1씩 증가시키면서 증가시키는 값을 저장하자.
		 * 변수에 계속 누적시켜 누적시킨 변수에 저장된 값을 출력하자.
		 */
		
		/* 1부터 10까지를 변수에 저장 */
		
		int num1 = 1;
		int num2 = 2;
		int num3 = 3;
		int num4 = 4;
		int num5 = 5;
		int num6 = 6;
		int num7 = 7;
		int num8 = 8;
		int num9 = 9;
		int num10 = 10;
		
		/* 더할 값을 누적시킬 변수 */
		int sum = 0;
		
		sum = sum + num1;
		sum += num2;
		sum += num3;
		sum += num4;
		sum += num5;
		sum += num6;
		sum += num7;
		sum += num8;
		sum += num9;
		sum += num10;
		
		System.out.println("sum: " + sum);
		
		/* 개선해 보자. */
		/*
		 * 반복할 내용
		 * 1. 변수에 1씩 증가하는 값 담기
		 * 2. 저장된 값을 sum에 누적시키기
		 * 
		 * 반복 횟수는? 1부터 10까지 1씩 증가(10번 반복)
		 * 
		 * 반복하지 않을 내용
		 * 1. 값을 누적해서 저장 할 sum 변수 선언
		 * 2. sum에 누적된 값 출력
		 */
		
		int sum2 = 0;
		
		for(int i = 1; i <= 10; i++) {
			sum2 += i;
		}
		
		System.out.println("sum2: " + sum2);
	}

	public void testForExample3() {
		
		/*
		 * 5 ~ 10 사이의 난수를 발생 시켜서
		 * 1부터 발생한 난수까지의 합계를 구해보자.
		 */
		
		int random = (int)(Math.random() * 6) + 5;
		
		System.out.println("발생한 난수: " + random);
		
		int sum = 0;
		
		if(random == 5) {
			sum += 1;
			sum += 2;
			sum += 3;
			sum += 4;
			sum += 5;
		} else if (random == 6) {
			sum += 1;
			sum += 2;
			sum += 3;
			sum += 4;
			sum += 5;
			sum += 6;
		} else if (random == 7) {
			sum += 1;
			sum += 2;
			sum += 3;
			sum += 4;
			sum += 5;
			sum += 6;
			sum += 7;
		} else if (random == 8) {
			sum += 1;
			sum += 2;
			sum += 3;
			sum += 4;
			sum += 5;
			sum += 6;
			sum += 7;
			sum += 8;
		} else if (random == 9) {
			sum += 1;
			sum += 2;
			sum += 3;
			sum += 4;
			sum += 5;
			sum += 6;
			sum += 7;
			sum += 8;
			sum += 9;
		} else { 						// random == 10일 때
			sum += 1;
			sum += 2;
			sum += 3;
			sum += 4;
			sum += 5;
			sum += 6;
			sum += 7;
			sum += 8;
			sum += 9;
			sum += 10;
		}
		
		System.out.println("1부터 " + random + "까지의 합은" + sum);
		
		/* for문으로 개선해 보자. */
		int sum2 = 0;
		
		for(int i = 0 ; i <= random; i++) {
			sum2 += i;
		}
		System.out.println("1부터 " + random + "까지의 합은 " + sum2);
	}
}

